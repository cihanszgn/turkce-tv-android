// JNI bridge between the measurement app and libchiaki: pairing, wake-up and one video session.
// libchiaki calls back on its own threads, so samples go into a queue that Kotlin drains; there are
// no upcalls into the JVM. One session at a time, which is all the probe needs.
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <chiaki/discovery.h>
#include <chiaki/regist.h>
#include <chiaki/session.h>

#define TAG "Chiaki"
#define MAX_QUEUED 240
#define KEY_SIZE 16
#define REGIST_TIMEOUT_S 40

enum { STATE_IDLE, STATE_CONNECTING, STATE_CONNECTED, STATE_ENDED };

typedef struct sample_t {
	struct sample_t *next;
	size_t size;
	uint8_t data[];
} Sample;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static Sample *head, *tail;
static int queued, state = STATE_IDLE;
static int64_t samples, bytes, frames_lost, frames_recovered, dropped;
static char quit_reason[256];

static ChiakiLog chiaki_logger;
static ChiakiSession session;
static bool session_open;

static bool regist_done, regist_ok;
static ChiakiRegisteredHost regist_host;

static void log_cb(ChiakiLogLevel level, const char *msg, void *user)
{
	(void)user;
	int priority = level == CHIAKI_LOG_ERROR ? ANDROID_LOG_ERROR
		: level == CHIAKI_LOG_WARNING ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
	__android_log_write(priority, TAG, msg);
}

static bool library_ready(void)
{
	static bool ready;
	if(!ready)
	{
		chiaki_log_init(&chiaki_logger, CHIAKI_LOG_ERROR | CHIAKI_LOG_WARNING | CHIAKI_LOG_INFO, log_cb, NULL);
		ready = chiaki_lib_init() == CHIAKI_ERR_SUCCESS;
	}
	return ready;
}

static struct timespec deadline_ms(int ms)
{
	struct timespec at;
	clock_gettime(CLOCK_REALTIME, &at);
	at.tv_sec += ms / 1000;
	at.tv_nsec += (long)(ms % 1000) * 1000000L;
	if(at.tv_nsec >= 1000000000L)
	{
		at.tv_sec++;
		at.tv_nsec -= 1000000000L;
	}
	return at;
}

static void regist_cb(ChiakiRegistEvent *event, void *user)
{
	(void)user;
	pthread_mutex_lock(&lock);
	regist_ok = event->type == CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS;
	if(regist_ok)
		regist_host = *event->registered_host;
	regist_done = true;
	pthread_cond_broadcast(&changed);
	pthread_mutex_unlock(&lock);
}

/** Pairs with the PS5. Returns regist key + rp key (32 bytes), or null. Blocks until done. */
JNIEXPORT jbyteArray JNICALL Java_dev_turkce_olcum_ChiakiBridge_regist(
	JNIEnv *env, jobject thiz, jstring host, jbyteArray account, jint pin)
{
	(void)thiz;
	if(!library_ready() || (*env)->GetArrayLength(env, account) != CHIAKI_PSN_ACCOUNT_ID_SIZE)
		return NULL;

	ChiakiRegistInfo info;
	memset(&info, 0, sizeof(info));
	info.target = CHIAKI_TARGET_PS5_1;
	info.broadcast = false;
	info.pin = (uint32_t)pin;
	(*env)->GetByteArrayRegion(env, account, 0, CHIAKI_PSN_ACCOUNT_ID_SIZE, (jbyte *)info.psn_account_id);
	info.host = (*env)->GetStringUTFChars(env, host, NULL);

	regist_done = false;
	regist_ok = false;
	static ChiakiRegist regist;
	const char *address = info.host;
	ChiakiErrorCode err = chiaki_regist_start(&regist, &chiaki_logger, &info, regist_cb, NULL);
	(*env)->ReleaseStringUTFChars(env, host, address);
	if(err != CHIAKI_ERR_SUCCESS)
		return NULL;

	struct timespec until = deadline_ms(REGIST_TIMEOUT_S * 1000);
	pthread_mutex_lock(&lock);
	int waited = 0;
	while(!regist_done && waited == 0)
		waited = pthread_cond_timedwait(&changed, &lock, &until);
	bool finished = regist_done;
	pthread_mutex_unlock(&lock);
	if(!finished)
		chiaki_regist_stop(&regist);
	chiaki_regist_fini(&regist);
	if(!regist_ok)
		return NULL;

	jbyteArray keys = (*env)->NewByteArray(env, KEY_SIZE * 2);
	(*env)->SetByteArrayRegion(env, keys, 0, KEY_SIZE, (const jbyte *)regist_host.rp_regist_key);
	(*env)->SetByteArrayRegion(env, keys, KEY_SIZE, KEY_SIZE, (const jbyte *)regist_host.rp_key);
	return keys;
}

/** Wakes a PS5 from rest mode. The credential is the regist key read as hex text. */
JNIEXPORT jboolean JNICALL Java_dev_turkce_olcum_ChiakiBridge_wake(
	JNIEnv *env, jobject thiz, jstring host, jbyteArray registKey)
{
	(void)thiz;
	if(!library_ready() || (*env)->GetArrayLength(env, registKey) != KEY_SIZE)
		return JNI_FALSE;
	char text[KEY_SIZE + 1] = {0};
	(*env)->GetByteArrayRegion(env, registKey, 0, KEY_SIZE, (jbyte *)text);
	const char *address = (*env)->GetStringUTFChars(env, host, NULL);
	int err = chiaki_discovery_wakeup(&chiaki_logger, NULL, address, strtoull(text, NULL, 16), true);
	(*env)->ReleaseStringUTFChars(env, host, address);
	return err == CHIAKI_ERR_SUCCESS;
}

static void event_cb(ChiakiEvent *event, void *user)
{
	(void)user;
	pthread_mutex_lock(&lock);
	if(event->type == CHIAKI_EVENT_CONNECTED)
		state = STATE_CONNECTED;
	else if(event->type == CHIAKI_EVENT_QUIT)
	{
		state = STATE_ENDED;
		snprintf(quit_reason, sizeof(quit_reason), "%s: %s", chiaki_quit_reason_string(event->quit.reason),
			event->quit.reason_str ? event->quit.reason_str : "");
	}
	pthread_cond_broadcast(&changed);
	pthread_mutex_unlock(&lock);
}

static bool sample_cb(uint8_t *buf, size_t size, int32_t lost, bool recovered, void *user)
{
	(void)user;
	Sample *sample = malloc(sizeof(Sample) + size);
	if(!sample)
		return false;
	sample->next = NULL;
	sample->size = size;
	memcpy(sample->data, buf, size);

	pthread_mutex_lock(&lock);
	samples++;
	bytes += (int64_t)size;
	frames_lost += lost;
	frames_recovered += recovered ? 1 : 0;
	if(queued >= MAX_QUEUED)
	{
		dropped++;
		free(sample);
	}
	else
	{
		if(tail)
			tail->next = sample;
		else
			head = sample;
		tail = sample;
		queued++;
		pthread_cond_broadcast(&changed);
	}
	pthread_mutex_unlock(&lock);
	return true;
}

static void clear_queue(void)
{
	while(head)
	{
		Sample *next = head->next;
		free(head);
		head = next;
	}
	tail = NULL;
	queued = 0;
}

static void set_state(int value)
{
	pthread_mutex_lock(&lock);
	state = value;
	pthread_mutex_unlock(&lock);
}

JNIEXPORT jboolean JNICALL Java_dev_turkce_olcum_ChiakiBridge_start(
	JNIEnv *env, jobject thiz, jstring host, jbyteArray registKey, jbyteArray rpKey, jbyteArray account,
	jint width, jint height, jint fps, jint bitrateKbps)
{
	(void)thiz;
	if(!library_ready() || session_open)
		return JNI_FALSE;

	ChiakiConnectInfo info;
	memset(&info, 0, sizeof(info));
	info.ps5 = true;
	info.video_profile.width = (unsigned int)width;
	info.video_profile.height = (unsigned int)height;
	info.video_profile.max_fps = (unsigned int)fps;
	info.video_profile.bitrate = (unsigned int)bitrateKbps;
	info.video_profile.codec = CHIAKI_CODEC_H264;
	info.video_profile_auto_downgrade = true;
	info.audio_video_disabled = CHIAKI_NONE_DISABLED;
	info.packet_loss_max = 0.05;
	info.enable_idr_on_fec_failure = true;
	(*env)->GetByteArrayRegion(env, registKey, 0, KEY_SIZE, (jbyte *)info.regist_key);
	(*env)->GetByteArrayRegion(env, rpKey, 0, KEY_SIZE, (jbyte *)info.morning);
	(*env)->GetByteArrayRegion(env, account, 0, CHIAKI_PSN_ACCOUNT_ID_SIZE, (jbyte *)info.psn_account_id);
	const char *address = (*env)->GetStringUTFChars(env, host, NULL);
	info.host = address;

	pthread_mutex_lock(&lock);
	clear_queue();
	samples = bytes = frames_lost = frames_recovered = dropped = 0;
	quit_reason[0] = '\0';
	state = STATE_CONNECTING;
	pthread_mutex_unlock(&lock);

	ChiakiErrorCode err = chiaki_session_init(&session, &info, &chiaki_logger);
	(*env)->ReleaseStringUTFChars(env, host, address);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		set_state(STATE_IDLE);
		return JNI_FALSE;
	}
	chiaki_session_set_event_cb(&session, event_cb, NULL);
	chiaki_session_set_video_sample_cb(&session, sample_cb, NULL);
	session_open = true;
	if(chiaki_session_start(&session) != CHIAKI_ERR_SUCCESS)
	{
		chiaki_session_fini(&session);
		session_open = false;
		set_state(STATE_IDLE);
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_dev_turkce_olcum_ChiakiBridge_state(JNIEnv *env, jobject thiz)
{
	(void)env;
	(void)thiz;
	pthread_mutex_lock(&lock);
	int current = state;
	pthread_mutex_unlock(&lock);
	return current;
}

JNIEXPORT jstring JNICALL Java_dev_turkce_olcum_ChiakiBridge_quitReason(JNIEnv *env, jobject thiz)
{
	(void)thiz;
	char text[sizeof(quit_reason)];
	pthread_mutex_lock(&lock);
	memcpy(text, quit_reason, sizeof(text));
	pthread_mutex_unlock(&lock);
	return (*env)->NewStringUTF(env, text);
}

/** Copies the next sample into a direct buffer: its size, 0 when none came in time, -1 when too big. */
JNIEXPORT jint JNICALL Java_dev_turkce_olcum_ChiakiBridge_nextSample(
	JNIEnv *env, jobject thiz, jobject target, jint timeoutMs)
{
	(void)thiz;
	uint8_t *destination = (*env)->GetDirectBufferAddress(env, target);
	jlong capacity = (*env)->GetDirectBufferCapacity(env, target);

	pthread_mutex_lock(&lock);
	if(!head && timeoutMs > 0)
	{
		struct timespec until = deadline_ms(timeoutMs);
		int waited = 0;
		while(!head && waited == 0)
			waited = pthread_cond_timedwait(&changed, &lock, &until);
	}
	Sample *sample = head;
	if(sample)
	{
		head = sample->next;
		if(!head)
			tail = NULL;
		queued--;
	}
	pthread_mutex_unlock(&lock);
	if(!sample)
		return 0;

	jint size = -1;
	if(destination && (jlong)sample->size <= capacity)
	{
		memcpy(destination, sample->data, sample->size);
		size = (jint)sample->size;
	}
	free(sample);
	return size;
}

/** samples, bytes, frames lost, frames recovered, samples dropped, samples queued */
JNIEXPORT jlongArray JNICALL Java_dev_turkce_olcum_ChiakiBridge_stats(JNIEnv *env, jobject thiz)
{
	(void)thiz;
	pthread_mutex_lock(&lock);
	jlong values[] = { samples, bytes, frames_lost, frames_recovered, dropped, queued };
	pthread_mutex_unlock(&lock);
	jlongArray result = (*env)->NewLongArray(env, 6);
	(*env)->SetLongArrayRegion(env, result, 0, 6, values);
	return result;
}

JNIEXPORT void JNICALL Java_dev_turkce_olcum_ChiakiBridge_requestIdr(JNIEnv *env, jobject thiz)
{
	(void)env;
	(void)thiz;
	if(session_open)
		chiaki_session_request_idr(&session);
}

JNIEXPORT void JNICALL Java_dev_turkce_olcum_ChiakiBridge_stop(JNIEnv *env, jobject thiz)
{
	(void)env;
	(void)thiz;
	if(!session_open)
		return;
	chiaki_session_stop(&session);
	chiaki_session_join(&session);
	chiaki_session_fini(&session);
	session_open = false;
	pthread_mutex_lock(&lock);
	clear_queue();
	state = STATE_IDLE;
	pthread_mutex_unlock(&lock);
}
