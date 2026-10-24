/* libusb autoconf 설정의 최소 대체본.
 * Android/NDK에서는 clock_gettime이 bionic에 있으므로 켜둔다. */
#ifndef BITPERFECT_LIBUSB_CONFIG_H
#define BITPERFECT_LIBUSB_CONFIG_H
#define HAVE_CLOCK_GETTIME 1
#endif
