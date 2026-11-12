// Stub for PSN hole punching (remote access over the internet), which this app does not use.
// The upstream holepunch.c needs curl/json-c/miniupnpc/libevent, so it is left out of the build and
// the symbols libchiaki references are filled with "unsupported". session->holepunch_session is
// always NULL, so none of these is actually called.

#include <netinet/in.h>
#include <chiaki/remote/holepunch.h>

#include <string.h>

CHIAKI_EXPORT ChiakiErrorCode chiaki_holepunch_list_devices(const char *psn_oauth2_token,
	ChiakiHolepunchConsoleType console_type, ChiakiHolepunchDeviceInfo **devices,
	size_t *device_count, ChiakiLog *log)
{
	*devices = NULL;
	*device_count = 0;
	return CHIAKI_ERR_INVALID_DATA;
}

CHIAKI_EXPORT void chiaki_holepunch_free_device_list(ChiakiHolepunchDeviceInfo **devices) {}

CHIAKI_EXPORT ChiakiHolepunchRegistInfo chiaki_get_regist_info(ChiakiHolepunchSession session)
{
	ChiakiHolepunchRegistInfo info;
	memset(&info, 0, sizeof(info));
	return info;
}

CHIAKI_EXPORT void chiaki_get_ps_selected_addr(ChiakiHolepunchSession session, char *ps_ip) {}

CHIAKI_EXPORT uint16_t chiaki_get_ps_ctrl_port(ChiakiHolepunchSession session) { return 0; }

CHIAKI_EXPORT chiaki_socket_t *chiaki_get_holepunch_sock(ChiakiHolepunchSession session, ChiakiHolepunchPortType type)
{
	return NULL;
}

CHIAKI_EXPORT bool chiaki_holepunch_session_get_stun_allocation(ChiakiHolepunchSession session,
	int32_t *allocation_increment, bool *random_allocation)
{
	return false;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_holepunch_generate_client_device_uid(char *out, size_t *out_size)
{
	return CHIAKI_ERR_INVALID_DATA;
}

CHIAKI_EXPORT ChiakiHolepunchSession chiaki_holepunch_session_init(const char *psn_oauth2_token, ChiakiLog *log)
{
	return NULL;
}

CHIAKI_EXPORT void chiaki_holepunch_session_force_port_guessing(ChiakiHolepunchSession session, bool enabled) {}

CHIAKI_EXPORT void chiaki_holepunch_session_set_port_guessing_ports(ChiakiHolepunchSession session, int count) {}

CHIAKI_EXPORT void chiaki_holepunch_session_set_port_guessing_socks(ChiakiHolepunchSession session, int count) {}

CHIAKI_EXPORT ChiakiErrorCode chiaki_holepunch_session_create(ChiakiHolepunchSession session)
{
	return CHIAKI_ERR_INVALID_DATA;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_holepunch_session_start(ChiakiHolepunchSession session,
	const uint8_t *console_uid, ChiakiHolepunchConsoleType console_type)
{
	return CHIAKI_ERR_INVALID_DATA;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_holepunch_upnp_discover(ChiakiHolepunchSession session)
{
	return CHIAKI_ERR_INVALID_DATA;
}

CHIAKI_EXPORT ChiakiErrorCode holepunch_session_create_offer(ChiakiHolepunchSession session)
{
	return CHIAKI_ERR_INVALID_DATA;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_holepunch_session_punch_hole(ChiakiHolepunchSession session,
	ChiakiHolepunchPortType port_type)
{
	return CHIAKI_ERR_INVALID_DATA;
}

CHIAKI_EXPORT void chiaki_holepunch_main_thread_cancel(ChiakiHolepunchSession session, bool stop_thread) {}

CHIAKI_EXPORT void chiaki_holepunch_session_fini(ChiakiHolepunchSession session) {}
