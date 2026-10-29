#include <jni.h>
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <unistd.h>

/*
 * Deliberate sinkhole drain helper only: reads and discards one TUN packet.
 * It is not a tun2socks forwarder and must only be used when the VPN is in
 * BLOCK-only sinkhole mode. No JVM-side packet buffer allocation is required.
 */
JNIEXPORT jint JNICALL
Java_com_netlimiterpro_service_Tun2SocksBridge_nativeDrainSinkholePacket(
        JNIEnv *env, jobject thiz, jint tun_fd, jint timeout_ms) {
    (void) env;
    (void) thiz;
    if (tun_fd < 0 || timeout_ms < 0) return -EINVAL;

    struct pollfd descriptor;
    descriptor.fd = tun_fd;
    descriptor.events = POLLIN;
    descriptor.revents = 0;

    int ready;
    do {
        ready = poll(&descriptor, 1, timeout_ms);
    } while (ready < 0 && errno == EINTR);

    if (ready == 0) return 0;
    if (ready < 0) return -errno;
    if ((descriptor.revents & (POLLERR | POLLNVAL)) != 0) return -EIO;
    if ((descriptor.revents & POLLIN) == 0) return 0;

    uint8_t buf[4096];
    ssize_t count;
    do {
        count = read(tun_fd, buf, sizeof(buf));
    } while (count < 0 && errno == EINTR);

    if (count < 0) return -errno;
    return (jint) count;
}

/* Kept fail-closed until a real, audited tun2socks engine is linked. */
JNIEXPORT jint JNICALL
Java_com_netlimiterpro_service_Tun2SocksBridge_nativeStartTun2Socks(
        JNIEnv *env, jobject thiz, jint tun_fd, jstring socks_host,
        jint socks_port, jint mtu) {
    (void) env; (void) thiz; (void) tun_fd; (void) socks_host;
    (void) socks_port; (void) mtu;
    return -1;
}

JNIEXPORT void JNICALL
Java_com_netlimiterpro_service_Tun2SocksBridge_nativeStopTun2Socks(
        JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
}
