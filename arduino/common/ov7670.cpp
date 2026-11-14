// ov7670.cpp — minimal OV7670 SCCB driver for the arducam test firmware.
// Sources of register values: see ov7670.h.
//
// This is a .cpp file (not .c) because it uses the C++ Wire library.

#include <Wire.h>
#include <avr/pgmspace.h>
#include "ov7670.h"

// ---------------------------------------------------------------------------
// Tables
// ---------------------------------------------------------------------------

// Linux kernel ov7670.c: ov7670_default_regs[], copied verbatim.
// "The default register settings, as obtained from OmniVision. There is
//  really no making sense of most of these - lots of "reserved" values and
//  such. These settings give VGA YUYV."
static const ov7670_regval PROGMEM ov7670_default_regs[] = {
  { REG_COM7, COM7_RESET },
  // Clock scale: 3 = 15fps, 2 = 20fps, 1 = 30fps (at 24 MHz XCLK + PLL)
  { REG_CLKRC, 0x1 },     // OV: clock scale (30 fps)
  { REG_TSLB,  0x04 },    // OV
  { REG_COM7, 0 },        // VGA
  // Set the hardware window. These values from OV don't entirely make
  // sense - hstop is less than hstart. But they work...
  { REG_HSTART, 0x13 },   { REG_HSTOP, 0x01 },
  { REG_HREF, 0xb6 },     { REG_VSTART, 0x02 },
  { REG_VSTOP, 0x7a },    { REG_VREF, 0x0a },

  { REG_COM3, 0 },        { REG_COM14, 0 },
  // Mystery scaling numbers
  { REG_SCALING_XSC, 0x3a },
  { REG_SCALING_YSC, 0x35 },
  { 0x72, 0x11 },         { 0x73, 0xf0 },
  { 0xa2, 0x02 },         { REG_COM10, 0x0 },

  // Gamma curve values
  { 0x7a, 0x20 },         { 0x7b, 0x10 },
  { 0x7c, 0x1e },         { 0x7d, 0x35 },
  { 0x7e, 0x5a },         { 0x7f, 0x69 },
  { 0x80, 0x76 },         { 0x81, 0x80 },
  { 0x82, 0x88 },         { 0x83, 0x8f },
  { 0x84, 0x96 },         { 0x85, 0xa3 },
  { 0x86, 0xaf },         { 0x87, 0xc4 },
  { 0x88, 0xd7 },         { 0x89, 0xe8 },

  // AGC and AEC parameters. Note we start by disabling those features,
  // then turn them only after tweaking the values.
  { REG_COM8, COM8_FASTAEC | COM8_AECSTEP | COM8_BFILT },
  { REG_GAIN, 0 },        { REG_AECH, 0 },
  { REG_COM4, 0x40 },     // magic reserved bit
  { REG_COM9, 0x18 },     // 4x gain + magic rsvd bit
  { REG_BD50MAX, 0x05 },  { REG_BD60MAX, 0x07 },
  { REG_AEW, 0x95 },      { REG_AEB, 0x33 },
  { REG_VPT, 0xe3 },      { REG_HAECC1, 0x78 },
  { REG_HAECC2, 0x68 },   { 0xa1, 0x03 },  // magic
  { REG_HAECC3, 0xd8 },   { REG_HAECC4, 0xd8 },
  { REG_HAECC5, 0xf0 },   { REG_HAECC6, 0x90 },
  { REG_HAECC7, 0x94 },
  { REG_COM8, COM8_FASTAEC | COM8_AECSTEP | COM8_BFILT | COM8_AGC | COM8_AEC },

  // Almost all of these are magic "reserved" values.
  { REG_COM5, 0x61 },     { REG_COM6, 0x4b },
  { 0x16, 0x02 },         { REG_MVFP, 0x07 },
  { 0x21, 0x02 },         { 0x22, 0x91 },
  { 0x29, 0x07 },         { 0x33, 0x0b },
  { 0x35, 0x0b },         { 0x37, 0x1d },
  { 0x38, 0x71 },         { 0x39, 0x2a },
  { REG_COM12, 0x78 },    { 0x4d, 0x40 },
  { 0x4e, 0x20 },         { REG_GFIX, 0 },
  { 0x6b, 0x4a },         { 0x74, 0x10 },   // 0x6b (DBLV) overridden below
  { 0x8d, 0x4f },         { 0x8e, 0 },
  { 0x8f, 0 },            { 0x90, 0 },
  { 0x91, 0 },            { 0x96, 0 },
  { 0x9a, 0 },            { 0xb0, 0x84 },
  { 0xb1, 0x0c },         { 0xb2, 0x0e },
  { 0xb3, 0x82 },         { 0xb8, 0x0a },

  // More reserved magic, some of which tweaks white balance
  { 0x43, 0x0a },         { 0x44, 0xf0 },
  { 0x45, 0x34 },         { 0x46, 0x58 },
  { 0x47, 0x28 },         { 0x48, 0x3a },
  { 0x59, 0x88 },         { 0x5a, 0x88 },
  { 0x5b, 0x44 },         { 0x5c, 0x67 },
  { 0x5d, 0x49 },         { 0x5e, 0x0e },
  { 0x6c, 0x0a },         { 0x6d, 0x55 },
  { 0x6e, 0x11 },         { 0x6f, 0x9f },   // "9e for advance AWB"
  { 0x6a, 0x40 },         { REG_BLUE, 0x40 },
  { REG_RED, 0x60 },
  { REG_COM8, COM8_FASTAEC | COM8_AECSTEP | COM8_BFILT | COM8_AGC | COM8_AEC | COM8_AWB },

  // Matrix coefficients
  { 0x4f, 0x80 },         { 0x50, 0x80 },
  { 0x51, 0 },            { 0x52, 0x22 },
  { 0x53, 0x5e },         { 0x54, 0x80 },
  { 0x58, 0x9e },

  { REG_COM16, COM16_AWBGAIN }, { REG_EDGE, 0 },
  { 0x75, 0x05 },         { 0x76, 0xe1 },
  { 0x4c, 0 },            { 0x77, 0x01 },
  { REG_COM13, 0xc3 },    { 0x4b, 0x09 },
  { 0xc9, 0x60 },         { REG_COM16, 0x38 },
  { 0x56, 0x40 },

  { 0x34, 0x11 },         { REG_COM11, COM11_EXP | COM11_HZAUTO },
  { 0xa4, 0x88 },         { 0x96, 0 },
  { 0x97, 0x30 },         { 0x98, 0x20 },
  { 0x99, 0x30 },         { 0x9a, 0x84 },
  { 0x9b, 0x29 },         { 0x9c, 0x03 },
  { 0x9d, 0x4c },         { 0x9e, 0x3f },
  { 0x78, 0x04 },

  // Extra-weird stuff. Some sort of multiplexor register
  { 0x79, 0x01 },         { 0xc8, 0xf0 },
  { 0x79, 0x0f },         { 0xc8, 0x00 },
  { 0x79, 0x10 },         { 0xc8, 0x7e },
  { 0x79, 0x0a },         { 0xc8, 0x80 },
  { 0x79, 0x0b },         { 0xc8, 0x01 },
  { 0x79, 0x0c },         { 0xc8, 0x0f },
  { 0x79, 0x0d },         { 0xc8, 0x20 },
  { 0x79, 0x09 },         { 0xc8, 0x80 },
  { 0x79, 0x02 },         { 0xc8, 0xc0 },
  { 0x79, 0x03 },         { 0xc8, 0x40 },
  { 0x79, 0x05 },         { 0xc8, 0x30 },
  { 0x79, 0x26 },

  { OV7670_END, OV7670_END },
};

// Linux kernel ov7670.c: ov7670_fmt_yuv422[], copied verbatim.
// The byte order (COM13 bit 0) is overridden in ov7670_overrides[] below.
static const ov7670_regval PROGMEM ov7670_fmt_yuv422[] = {
  { REG_COM7, 0x0 },      // Selects YUV mode
  { REG_RGB444, 0 },      // No RGB444 please
  { REG_COM1, 0 },        // CCIR601
  { REG_COM15, COM15_R00FF },
  { REG_COM9, 0x48 },     // 32x gain ceiling; 0x8 is reserved bit
  { 0x4f, 0x80 },         // "matrix coefficient 1"
  { 0x50, 0x80 },         // "matrix coefficient 2"
  { 0x51, 0    },         // vb
  { 0x52, 0x22 },         // "matrix coefficient 4"
  { 0x53, 0x5e },         // "matrix coefficient 5"
  { 0x54, 0x80 },         // "matrix coefficient 6"
  { REG_COM13, COM13_GAMMA | COM13_UVSAT },
  { OV7670_END, OV7670_END },
};

// Window: the kernel's VGA window (ov7670_win_sizes[0]: hstart 158,
// hstop 14, vstart 10, vstop 490), encoded the way ov7670_set_hw() does it.
// Width = 784 - 158 + 14 = 640, height = 490 - 10 = 480; the DCW
// down-sampling divides both by 2 (QVGA, 320 x 240) or 4 (QQVGA, 160 x 120);
// VGA Bayer uses it unscaled.
// The top bits of HREF (0x80) and VREF (0x0) are kept from the default table,
// as ov7670_set_hw() does with read-modify-write.
#define WIN_HSTART 158
#define WIN_HSTOP   14
#define WIN_VSTART  10
#define WIN_VSTOP  490
static const ov7670_regval PROGMEM ov7670_window_vga[] = {
  { REG_HSTART, (WIN_HSTART >> 3) & 0xff },
  { REG_HSTOP,  (WIN_HSTOP  >> 3) & 0xff },
  { REG_HREF,   0x80 | ((WIN_HSTOP & 0x7) << 3) | (WIN_HSTART & 0x7) },
  { REG_VSTART, (WIN_VSTART >> 2) & 0xff },
  { REG_VSTOP,  (WIN_VSTOP  >> 2) & 0xff },
  { REG_VREF,   0x00 | ((WIN_VSTOP & 0x3) << 2) | (WIN_VSTART & 0x3) },
  { OV7670_END, OV7670_END },
};

// Linux kernel ov7670.c: ov7670_fmt_raw[], copied verbatim (raw Bayer
// BGGR, declared as MEDIA_BUS_FMT_SBGGR8_1X8 by the kernel).
static const ov7670_regval PROGMEM ov7670_fmt_raw[] = {
  { REG_COM7, COM7_BAYER },
  { REG_COM13, 0x08 },    // No gamma, magic rsvd bit
  { REG_COM16, 0x3d },    // Edge enhancement, denoise
  { REG_REG76, 0xe1 },    // Pix correction, magic rsvd
  { OV7670_END, OV7670_END },
};

// Mandatory overrides for this hardware (all modes).
static const ov7670_regval PROGMEM ov7670_overrides[] = {
  { REG_DBLV, DBLV_BYPASS },   // bits 7:6 = 00 -> PLL bypassed
  { REG_COM10, 0x00 },         // PCLK free-running in blanking, VSYNC
                               // positive, HREF (not HSYNC), PCLK normal
  { OV7670_END, OV7670_END },
};

// YUYV-only overrides.
static const ov7670_regval PROGMEM ov7670_overrides_yuyv[] = {
  { REG_COM15, COM15_R00FF },  // full range 00..FF, YUV (RGB bits off)
  // Byte order Y0 U0 Y1 V0. The datasheet says TSLB[3] = 0 + COM13[0] = 0
  // gives YUYV, but on the real hardware that produced Y V Y U (blue jeans
  // came out beige, a wooden floor blue), so the UV swap bit is set.
  { REG_TSLB, 0x04 },          // bit 3 = 0
  { REG_COM13, COM13_GAMMA | COM13_UVSAT | COM13_UVSWAP }, // bit 0 = 1
  { OV7670_END, OV7670_END },
};

// Per-mode scaling + CLKRC (written last, like the kernel does).
// DCW scaling values from LiveOV7670 CameraOV7670RegistersQQVGA.cpp /
// CameraOV7670RegistersQVGA.cpp (the kernel driver uses neither). LiveOV7670
// also uses slightly larger windows with padding; we keep the kernel's exact
// 640x480 window instead so that a line is exactly the mode's width.
// COM14[3] (manual scaling) must be 1 for 0x73 to take effect.
static const ov7670_regval PROGMEM ov7670_mode_qqvga[] = {
  { REG_COM3, COM3_DCWEN },          // enable downsamp/crop/window
  { REG_COM14, 0x1a },               // DCW/PCLK scaling enable, PCLK / 4
  { REG_SCALING_DCWCTR, 0x22 },      // downsample by 4 (H and V)
  { REG_SCALING_PCLK_DIV, 0xf2 },    // DSP clock / 4
  { REG_CLKRC, OV7670_MODE_CLKRC(OV7670_MODE_QQVGA_YUYV) },
  { OV7670_END, OV7670_END },
};
static const ov7670_regval PROGMEM ov7670_mode_qvga[] = {
  { REG_COM3, COM3_DCWEN },          // enable downsamp/crop/window
  { REG_COM14, 0x19 },               // DCW/PCLK scaling enable, PCLK / 2
  { REG_SCALING_DCWCTR, 0x11 },      // downsample by 2 (H and V)
  { REG_SCALING_PCLK_DIV, 0xf1 },    // DSP clock / 2
  { REG_CLKRC, OV7670_MODE_CLKRC(OV7670_MODE_QVGA_YUYV) },
  { OV7670_END, OV7670_END },
};
// VGA: no scaling (the kernel default values, restated explicitly).
static const ov7670_regval PROGMEM ov7670_mode_vga[] = {
  { REG_COM3, 0x00 },
  { REG_COM14, 0x00 },
  { REG_SCALING_DCWCTR, 0x11 },
  { REG_SCALING_PCLK_DIV, 0xf0 },
  { REG_CLKRC, OV7670_MODE_CLKRC(OV7670_MODE_VGA_BAYER) },
  { OV7670_END, OV7670_END },
};

// Empty table (Bayer has no format-specific overrides).
static const ov7670_regval PROGMEM ov7670_none[] = {
  { OV7670_END, OV7670_END },
};

// Table sequences per mode, in the order they are written.
#define N_CONFIG_TABLES 6
static const ov7670_regval *const mode_tables[OV7670_MODE_COUNT][N_CONFIG_TABLES] = {
  // QQVGA YUYV
  { ov7670_default_regs, ov7670_fmt_yuv422, ov7670_window_vga,
    ov7670_overrides, ov7670_overrides_yuyv, ov7670_mode_qqvga },
  // QVGA YUYV
  { ov7670_default_regs, ov7670_fmt_yuv422, ov7670_window_vga,
    ov7670_overrides, ov7670_overrides_yuyv, ov7670_mode_qvga },
  // VGA Bayer
  { ov7670_default_regs, ov7670_fmt_raw, ov7670_window_vga,
    ov7670_overrides, ov7670_none, ov7670_mode_vga },
};

// Tables of the last ov7670_configure(), used by ov7670_verify_config().
static const ov7670_regval *const *config_tables = mode_tables[0];

// ---------------------------------------------------------------------------
// Low level SCCB access
// ---------------------------------------------------------------------------

uint8_t ov7670_write(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(OV7670_I2C_ADDR);
  Wire.write(reg);
  Wire.write(val);
  uint8_t st = Wire.endTransmission(true);
  if (reg == REG_COM7 && (val & COM7_RESET)) {
    delay(10);  // wait for the reset to run (kernel waits 5 ms)
  }
  return st;
}

// SCCB read: write register address, STOP, then a separate read
// transaction (no repeated start).
uint8_t ov7670_read(uint8_t reg, uint8_t *val) {
  Wire.beginTransmission(OV7670_I2C_ADDR);
  Wire.write(reg);
  uint8_t st = Wire.endTransmission(true);
  if (st != 0) {
    return st;
  }
  if (Wire.requestFrom((uint8_t)OV7670_I2C_ADDR, (uint8_t)1) != 1) {
    return OV7670_ERR_NODATA;
  }
  *val = Wire.read();
  return 0;
}

uint8_t ov7670_update_bits(uint8_t reg, uint8_t mask, uint8_t val) {
  uint8_t v;
  uint8_t st = ov7670_read(reg, &v);
  if (st != 0) {
    return st;
  }
  v = (v & ~mask) | (val & mask);
  return ov7670_write(reg, v);
}

uint8_t ov7670_write_table(const ov7670_regval *table) {
  uint8_t failed = 0;
  for (;; table++) {
    uint8_t reg = pgm_read_byte(&table->reg);
    uint8_t val = pgm_read_byte(&table->val);
    if (reg == OV7670_END && val == OV7670_END) {
      break;
    }
    if (ov7670_write(reg, val) != 0) {
      failed++;
    }
  }
  return failed;
}

uint8_t ov7670_configure(uint8_t mode) {
  if (mode >= OV7670_MODE_COUNT) {
    return 0xff;
  }
  config_tables = mode_tables[mode];
  uint8_t failed = 0;
  for (uint8_t t = 0; t < N_CONFIG_TABLES; t++) {
    failed += ov7670_write_table(config_tables[t]);
  }
  return failed;
}

// ---------------------------------------------------------------------------
// Read-back verification
// ---------------------------------------------------------------------------

// Mask of bits that should read back exactly as written; 0 = skip register.
static uint8_t verify_mask(uint8_t reg) {
  switch (reg) {
    case REG_GAIN:   // AGC
    case REG_BLUE:   // AWB
    case REG_RED:    // AWB
    case REG_AECHH:  // AEC
    case REG_AECH:   // AEC
    case REG_GGAIN:  // AWB
    case 0x4c:       // DNSTH: de-noise threshold, auto (COM16[4] = 1)
    case 0xc8:       // indirect data register, depends on 0x79
      return 0x00;
    case REG_EDGE:   // EDGE[4:0]: edge enhancement, auto (COM16[5] = 1)
      return 0xe0;
    case 0xc9:       // SATCTR[3:0]: UV saturation result (COM13[6] = 1)
      return 0xf0;
    case REG_CLKRC:  // bit 7 reserved (default 1), may read back as 1
      return 0x7f;
    case REG_COM1:   // bits 1:0 = AEC[1:0]
      return 0xfc;
    case REG_VREF:   // bits 7:6 = AGC[9:8], 5:4 reserved
      return 0x0f;
    case REG_COM7:   // bit 7 = reset, self-clearing
      return 0x7f;
    case REG_COM11:  // bit 3 = 50/60 Hz, changed by auto detection
      return 0xf7;
    default:
      return 0xff;
  }
}

// Bits whose mismatch is critical (clock, format, window, HREF framing).
static uint8_t critical_mask(uint8_t reg) {
  switch (reg) {
    case REG_COM6:   // bit 7: HREF on optical black lines
    case REG_COM12:  // bit 7: HREF always (also during VSYNC)
      return 0x80;
    case REG_COM1:   // bit 6: CCIR656 (embedded sync codes)
      return COM1_CCIR656;
    case REG_CLKRC:
    case REG_DBLV:
    case REG_COM7:
    case REG_COM10:
    case REG_COM15:
    case REG_COM3:
    case REG_COM14:
    case REG_SCALING_DCWCTR:
    case REG_SCALING_PCLK_DIV:
    case REG_TSLB:
    case REG_COM13:
    case REG_HSTART:
    case REG_HSTOP:
    case REG_HREF:
    case REG_VSTART:
    case REG_VSTOP:
    case REG_VREF:
      return 0xff;
    default:
      return 0x00;
  }
}

// True if reg is written again after entry `idx` of table `t`.
static bool written_later(uint8_t t, uint16_t idx, uint8_t reg) {
  uint16_t start = idx + 1;
  for (; t < N_CONFIG_TABLES; t++) {
    const ov7670_regval *tab = config_tables[t];
    for (uint16_t i = start;; i++) {
      uint8_t r = pgm_read_byte(&tab[i].reg);
      uint8_t v = pgm_read_byte(&tab[i].val);
      if (r == OV7670_END && v == OV7670_END) {
        break;
      }
      if (r == reg) {
        return true;
      }
    }
    start = 0;  // following tables: check from entry 0
  }
  return false;
}

uint16_t ov7670_verify_config(ov7670_mismatch_cb cb, uint16_t *checked,
                              uint16_t *skipped) {
  uint16_t bad = 0;
  *checked = 0;
  *skipped = 0;
  for (uint8_t t = 0; t < N_CONFIG_TABLES; t++) {
    const ov7670_regval *tab = config_tables[t];
    for (uint16_t i = 0;; i++) {
      uint8_t reg = pgm_read_byte(&tab[i].reg);
      uint8_t expected = pgm_read_byte(&tab[i].val);
      if (reg == OV7670_END && expected == OV7670_END) {
        break;
      }
      if (written_later(t, i, reg)) {
        continue;  // only the last write counts
      }
      uint8_t mask = verify_mask(reg);
      if (mask == 0) {
        (*skipped)++;
        continue;
      }
      (*checked)++;
      uint8_t actual = 0;
      uint8_t st = ov7670_read(reg, &actual);
      uint8_t diff = (actual ^ expected) & mask;
      if (st != 0 || diff != 0) {
        bad++;
        bool critical = (st != 0) ? (critical_mask(reg) != 0)
                                  : ((diff & critical_mask(reg)) != 0);
        if (cb) {
          cb(reg, expected, actual, mask, st, critical);
        }
      }
    }
  }
  return bad;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// Same encoding as the kernel's ov7670_s_test_pattern(). The low 7 bits of
// both registers are scale factors and are preserved.
uint8_t ov7670_set_test_pattern(uint8_t pattern) {
  uint8_t st = ov7670_update_bits(REG_SCALING_XSC, TEST_PATTERN_0,
                                  (pattern & 0x01) ? TEST_PATTERN_0 : 0);
  if (st != 0) {
    return st;
  }
  return ov7670_update_bits(REG_SCALING_YSC, TEST_PATTERN_1,
                            (pattern & 0x02) ? TEST_PATTERN_1 : 0);
}

uint8_t ov7670_set_auto(bool on) {
  const uint8_t bits = COM8_AGC | COM8_AWB | COM8_AEC;
  return ov7670_update_bits(REG_COM8, bits, on ? bits : 0);
}

// COM17[7:6] (AEC window) must match COM4[5:4]; both are 00 with the kernel
// table (COM4 = 0x40), so only bit 3 is touched.
uint8_t ov7670_set_dsp_colour_bar(bool on) {
  return ov7670_update_bits(REG_COM17, COM17_CBAR, on ? COM17_CBAR : 0);
}
