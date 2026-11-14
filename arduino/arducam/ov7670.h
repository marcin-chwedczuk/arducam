// ov7670.h — minimal OV7670 SCCB driver for the arducam test firmware.
//
// Register names/addresses and the register tables in ov7670.cpp are taken
// from the Linux kernel driver drivers/media/i2c/ov7670.c
// (https://github.com/torvalds/linux/blob/master/drivers/media/i2c/ov7670.c),
// except the QQVGA/QVGA DCW scaling values (the kernel uses neither); those
// come from LiveOV7670 (https://github.com/indrekluuk/LiveOV7670,
// CameraOV7670RegistersQQVGA.cpp / CameraOV7670RegistersQVGA.cpp).

#ifndef OV7670_H
#define OV7670_H

#include <Arduino.h>

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

// Frequency of the external oscillator feeding XCLK.
#define OV7670_XCLK_HZ   8000000UL

// Output size (both YUYV, 2 bytes/pixel, from the 640x480 window scaled
// down by the DCW block):
//   0 = QQVGA 160x120 ( 38,400 bytes), PCLK = internal clock / 4
//   1 = QVGA  320x240 (153,600 bytes), PCLK = internal clock / 2
// (VGA YUYV = 614,400 bytes does not fit in the 512 KB SRAM.)
#define OV7670_QVGA      1

#if OV7670_QVGA
#define OV7670_WIDTH     320
#define OV7670_HEIGHT    240
#define OV7670_PCLK_DIV  2
#define OV7670_SIZE_NAME "QVGA"
#else
#define OV7670_WIDTH     160
#define OV7670_HEIGHT    120
#define OV7670_PCLK_DIV  4
#define OV7670_SIZE_NAME "QQVGA"
#endif

// CLKRC value written last. Bit 6 (use external clock directly) must stay 0.
// Chosen so PCLK = 500 kHz with the 8 MHz oscillator (breadboard-safe):
//   QQVGA: 0x01 -> internal 2 MHz / 4 -> 500 kHz, frame 400 ms
//   QVGA:  0x03 -> internal 1 MHz / 2 -> 500 kHz, frame 800 ms
#if OV7670_QVGA
#define CLKRC_VAL        0x03
#else
#define CLKRC_VAL        0x01
#endif

// Internal clock = XCLK / OV7670_CLK_DIV. The datasheet says
// XCLK / (CLKRC[5:0] + 1), but on this hardware the measured frame period is
// exactly twice that (398.7 ms with CLKRC = 0x01; the kernel driver notes
// the same extra /2 for the OV7675), so the firmware assumes the extra /2.
#define OV7670_CLK_DIV   (2UL * ((CLKRC_VAL & 0x3f) + 1))

// 7-bit SCCB address (0x42 write / 0x43 read).
#define OV7670_I2C_ADDR  0x21

// Error code returned by ov7670_read() when requestFrom() got no byte.
// (Codes 1..5 are Wire.endTransmission() codes: 2 = address NACK,
//  3 = data NACK, 4 = other error, 5 = timeout.)
#define OV7670_ERR_NODATA 6

// ---------------------------------------------------------------------------
// Registers (names from the Linux kernel driver)
// ---------------------------------------------------------------------------
#define REG_GAIN         0x00  // Gain lower 8 bits (rest in vref)
#define REG_BLUE         0x01  // blue gain
#define REG_RED          0x02  // red gain
#define REG_VREF         0x03  // Pieces of GAIN, VSTART, VSTOP
#define REG_COM1         0x04  // Control 1
#define   COM1_CCIR656     0x40  // CCIR656 enable
#define REG_BAVE         0x05  // U/B Average level
#define REG_GbAVE        0x06  // Y/Gb Average level
#define REG_AECHH        0x07  // AEC MS 5 bits
#define REG_RAVE         0x08  // V/R Average level
#define REG_COM2         0x09  // Control 2
#define   COM2_SSLEEP      0x10  // Soft sleep mode
#define REG_PID          0x0a  // Product ID MSB
#define REG_VER          0x0b  // Product ID LSB
#define REG_COM3         0x0c  // Control 3
#define   COM3_SWAP        0x40  // Byte swap
#define   COM3_SCALEEN     0x08  // Enable scaling
#define   COM3_DCWEN       0x04  // Enable downsamp/crop/window
#define REG_COM4         0x0d  // Control 4
#define REG_COM5         0x0e  // All "reserved"
#define REG_COM6         0x0f  // Control 6
#define REG_AECH         0x10  // More bits of AEC value
#define REG_CLKRC        0x11  // Clock control
#define   CLK_EXT          0x40  // Use external clock directly
#define   CLK_SCALE        0x3f  // Mask for internal clock scale
#define REG_COM7         0x12  // Control 7
#define   COM7_RESET       0x80  // Register reset
#define   COM7_FMT_MASK    0x38
#define   COM7_FMT_VGA     0x00
#define   COM7_FMT_CIF     0x20  // CIF format
#define   COM7_FMT_QVGA    0x10  // QVGA format
#define   COM7_FMT_QCIF    0x08  // QCIF format
#define   COM7_RGB         0x04  // bits 0 and 2 - RGB format
#define   COM7_YUV         0x00  // YUV
#define   COM7_BAYER       0x01  // Bayer format
#define   COM7_PBAYER      0x05  // "Processed bayer"
#define REG_COM8         0x13  // Control 8
#define   COM8_FASTAEC     0x80  // Enable fast AGC/AEC
#define   COM8_AECSTEP     0x40  // Unlimited AEC step size
#define   COM8_BFILT       0x20  // Band filter enable
#define   COM8_AGC         0x04  // Auto gain enable
#define   COM8_AWB         0x02  // White balance enable
#define   COM8_AEC         0x01  // Auto exposure enable
#define REG_COM9         0x14  // Control 9 - gain ceiling
#define REG_COM10        0x15  // Control 10
#define   COM10_HSYNC      0x40  // HSYNC instead of HREF
#define   COM10_PCLK_HB    0x20  // Suppress PCLK on horiz blank
#define   COM10_HREF_REV   0x08  // Reverse HREF
#define   COM10_VS_LEAD    0x04  // VSYNC on clock leading edge
#define   COM10_VS_NEG     0x02  // VSYNC negative
#define   COM10_HS_NEG     0x01  // HSYNC negative
#define REG_HSTART       0x17  // Horiz start high bits
#define REG_HSTOP        0x18  // Horiz stop high bits
#define REG_VSTART       0x19  // Vert start high bits
#define REG_VSTOP        0x1a  // Vert stop high bits
#define REG_PSHFT        0x1b  // Pixel delay after HREF
#define REG_MIDH         0x1c  // Manuf. ID high
#define REG_MIDL         0x1d  // Manuf. ID low
#define REG_MVFP         0x1e  // Mirror / vflip
#define   MVFP_MIRROR      0x20  // Mirror image
#define   MVFP_FLIP        0x10  // Vertical flip
#define REG_AEW          0x24  // AGC upper limit
#define REG_AEB          0x25  // AGC lower limit
#define REG_VPT          0x26  // AGC/AEC fast mode op region
#define REG_HSYST        0x30  // HSYNC rising edge delay
#define REG_HSYEN        0x31  // HSYNC falling edge delay
#define REG_HREF         0x32  // HREF pieces
#define REG_TSLB         0x3a  // lots of stuff
#define   TSLB_OUT_SEQ     0x08  // output sequence, together with COM13[0]
// (The kernel names bit 2 (0x04) "TSLB_YLAST", but the datasheet marks
//  TSLB[2:1] as reserved; the byte order is set by TSLB[3] + COM13[0].)
#define REG_COM11        0x3b  // Control 11
#define   COM11_NIGHT      0x80  // Night mode enable
#define   COM11_NMFR       0x60  // Two bit NM frame rate
#define   COM11_HZAUTO     0x10  // Auto detect 50/60 Hz
#define   COM11_50HZ       0x08  // Manual 50Hz select
#define   COM11_EXP        0x02
#define REG_COM12        0x3c  // Control 12
#define   COM12_HREF       0x80  // HREF always
#define REG_COM13        0x3d  // Control 13
#define   COM13_GAMMA      0x80  // Gamma enable
#define   COM13_UVSAT      0x40  // UV saturation auto adjustment
#define   COM13_UVSWAP     0x01  // V before U - w/TSLB
#define REG_COM14        0x3e  // Control 14
#define   COM14_DCWEN      0x10  // DCW/PCLK-scale enable
#define REG_EDGE         0x3f  // Edge enhancement factor
#define REG_COM15        0x40  // Control 15
#define   COM15_R10F0      0x00  // Data range 10 to F0
#define   COM15_R01FE      0x80  //            01 to FE
#define   COM15_R00FF      0xc0  //            00 to FF
#define   COM15_RGB565     0x10  // RGB565 output
#define   COM15_RGB555     0x30  // RGB555 output
#define REG_COM16        0x41  // Control 16
#define   COM16_AWBGAIN    0x08  // AWB gain enable
#define REG_COM17        0x42  // Control 17
#define   COM17_AECWIN     0xc0  // AEC window - must match COM4
#define   COM17_CBAR       0x08  // DSP Color bar
#define REG_CMATRIX_BASE 0x4f  // 0x4f..0x54 colour matrix
#define REG_BRIGHT       0x55  // Brightness
#define REG_CONTRAS      0x56  // Contrast control
#define REG_CMATRIX_SIGN 0x58
#define REG_GFIX         0x69  // Fix gain control
#define REG_GGAIN        0x6a  // G channel AWB gain (updated by AWB)
#define REG_DBLV         0x6b  // PLL control and debugging
#define   DBLV_BYPASS      0x0a  // Bypass PLL
#define   DBLV_X4          0x4a  // clock x4
#define   DBLV_X6          0x8a  // clock x6
#define   DBLV_X8          0xca  // clock x8
#define REG_SCALING_XSC  0x70  // Test pattern and horizontal scale factor
#define   TEST_PATTERN_0   0x80
#define REG_SCALING_YSC  0x71  // Test pattern and vertical scale factor
#define   TEST_PATTERN_1   0x80
#define REG_SCALING_DCWCTR    0x72  // DCW control (down-sampling)
#define REG_SCALING_PCLK_DIV  0x73  // DSP clock divider
#define REG_REG76        0x76  // OV's name
#define   R76_BLKPCOR      0x80  // Black pixel correction enable
#define   R76_WHTPCOR      0x40  // White pixel correction enable
#define REG_RGB444       0x8c  // RGB 444 control
#define   R444_ENABLE      0x02  // Turn on RGB444, overrides 5x5
#define   R444_RGBX        0x01  // Empty nibble at end
#define REG_HAECC1       0x9f  // Hist AEC/AGC control 1
#define REG_HAECC2       0xa0  // Hist AEC/AGC control 2
#define REG_SCALING_PCLK_DELAY 0xa2
#define REG_BD50MAX      0xa5  // 50hz banding step limit
#define REG_HAECC3       0xa6  // Hist AEC/AGC control 3
#define REG_HAECC4       0xa7  // Hist AEC/AGC control 4
#define REG_HAECC5       0xa8  // Hist AEC/AGC control 5
#define REG_HAECC6       0xa9  // Hist AEC/AGC control 6
#define REG_HAECC7       0xaa  // Hist AEC/AGC control 7
#define REG_BD60MAX      0xab  // 60hz banding step limit

// Test pattern values (kernel V4L2_CID_TEST_PATTERN encoding):
// bit 0 -> SCALING_XSC bit 7, bit 1 -> SCALING_YSC bit 7.
#define OV7670_PATTERN_NONE      0
#define OV7670_PATTERN_SHIFT1    1  // "Shifting 1"
#define OV7670_PATTERN_BARS      2  // "8-bar color bar"
#define OV7670_PATTERN_FADEBARS  3  // "Fade to gray color bar"

// ---------------------------------------------------------------------------
// Register tables
// ---------------------------------------------------------------------------
struct ov7670_regval {
  uint8_t reg;
  uint8_t val;
};
#define OV7670_END 0xff   // {0xff, 0xff} terminates a table (as in the kernel)

// ---------------------------------------------------------------------------
// API. Functions returning uint8_t return 0 on success, otherwise a Wire
// error code (see OV7670_ERR_NODATA above).
// ---------------------------------------------------------------------------
uint8_t ov7670_write(uint8_t reg, uint8_t val);
uint8_t ov7670_read(uint8_t reg, uint8_t *val);
// Read-modify-write: only the bits in mask are replaced by val.
uint8_t ov7670_update_bits(uint8_t reg, uint8_t mask, uint8_t val);

// Writes a PROGMEM table; returns the number of failed writes.
uint8_t ov7670_write_table(const ov7670_regval *table);

// Writes the complete YUYV configuration for the size selected by
// OV7670_QVGA: kernel defaults, kernel YUV422 format, window, DCW scaling,
// then the mandatory overrides (CLKRC, DBLV, COM10, COM15, byte order).
// Returns the number of failed writes.
uint8_t ov7670_configure_yuyv();

// Called by ov7670_verify_config() for every register that does not read
// back as expected. status != 0 means the read itself failed (Wire error).
// critical = a bit that controls clock/format/window/HREF differs
// (mismatch -> FAIL); otherwise only non-critical bits differ.
typedef void (*ov7670_mismatch_cb)(uint8_t reg, uint8_t expected,
                                   uint8_t actual, uint8_t mask,
                                   uint8_t status, bool critical);

// Reads back every register written by ov7670_configure_yuyv()
// (only the last value written to each register counts). Registers that the
// chip changes on its own (AEC/AGC/AWB) are skipped or masked.
// Returns number of mismatches (incl. read errors); *checked = registers
// compared, *skipped = registers excluded completely.
uint16_t ov7670_verify_config(ov7670_mismatch_cb cb, uint16_t *checked,
                              uint16_t *skipped);

// Test pattern, kernel encoding (OV7670_PATTERN_*).
uint8_t ov7670_set_test_pattern(uint8_t pattern);

// Enable/disable automatic exposure, gain and white balance (COM8).
uint8_t ov7670_set_auto(bool on);

// Enable/disable the DSP colour bar (COM17[3]), an alternative to the
// SCALING_XSC/YSC test pattern.
uint8_t ov7670_set_dsp_colour_bar(bool on);

#endif // OV7670_H
