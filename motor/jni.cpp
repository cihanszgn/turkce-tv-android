// Thin JNI layer over the Bergamot translator, for the TV measurement only.
#include <jni.h>

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

using namespace marian::bergamot;

namespace {

struct Engine {
  std::unique_ptr<BlockingService> service;
  std::shared_ptr<TranslationModel> model;
};

std::string toUtf8(JNIEnv *env, jbyteArray bytes) {
  const jsize size = env->GetArrayLength(bytes);
  std::string text(static_cast<size_t>(size), '\0');
  env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(&text[0]));
  return text;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_dev_turkce_olcum_BergamotTranslator_open(JNIEnv *env, jclass, jbyteArray yaml) {
  BlockingService::Config config;
  config.logger.level = "off";
  auto engine = new Engine();
  engine->service = std::make_unique<BlockingService>(config);
  auto options = parseOptionsFromString(toUtf8(env, yaml), /*validate=*/true, /*pathsDir=*/"");
  engine->model = std::make_shared<TranslationModel>(options, /*replicas=*/1);
  return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_turkce_olcum_BergamotTranslator_translate(JNIEnv *env, jclass,
                                                                                          jlong handle,
                                                                                          jbyteArray source) {
  auto engine = reinterpret_cast<Engine *>(handle);
  std::vector<std::string> sources{toUtf8(env, source)};
  std::vector<ResponseOptions> responseOptions(1);
  responseOptions[0].HTML = false;
  responseOptions[0].qualityScores = false;
  responseOptions[0].alignment = false;
  responseOptions[0].sentenceMappings = false;
  auto responses = engine->service->translateMultiple(engine->model, std::move(sources), responseOptions);
  const std::string &text = responses[0].target.text;
  jbyteArray result = env->NewByteArray(static_cast<jsize>(text.size()));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
  return result;
}

extern "C" JNIEXPORT void JNICALL Java_dev_turkce_olcum_BergamotTranslator_close(JNIEnv *, jclass, jlong handle) {
  delete reinterpret_cast<Engine *>(handle);
}
