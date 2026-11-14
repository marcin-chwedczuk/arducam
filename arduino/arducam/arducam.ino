// arducam.ino — OV7670 snapshot camera firmware for the Java PC application.
//
// Implements the serial protocol in arduino/PROTOCOL.md (protocol version 1).
// The firmware is deliberately dumb: it executes one command at a time and
// never acts on its own. All decisions belong to the host.
//
// Board: Arduino Uno (ATmega328P, 16 MHz), uploaded with Atmel-ICE via ISP.
// Serial via an external USB-serial module on D0/D1 (no auto-reset).
//
// The OV7670 driver (ov7670.h / ov7670.cpp) is shared with the self-test
// sketch: both files are symlinks to ../common/.

#include <Wire.h>
#include <util/crc16.h>
#include "ov7670.h"

#define FW_VERSION     "0.3.0"
#define PROTO_VERSION  3
#define BAUD           250000

// ---------------------------------------------------------------------------
// Pins (D11..D13 are used by the ISP programmer: never touch them)
// ---------------------------------------------------------------------------
#define PIN_VSYNC      2   // PD2 in   5 V buffered, active HIGH
#define PIN_CAM_RESET  3   // PD3 out  LOW = reset, HIGH = run
#define PIN_CAM_PWDN   4   // PD4 out  HIGH = power down, LOW = on
#define PIN_ARST       5   // PD5 out  /ARST, counter clear (sync, active LOW)
#define PIN_MODE       6   // PD6 out  0 = CAPTURE, 1 = SEND
#define PIN_ACLK       8   // PB0 out  counter clock in SEND mode
#define PIN_PE         9   // PB1 out  74HC166 /PE (LOW = parallel load)
#define PIN_SRCLK     10   // PB2 out  74HC166 clock
#define PIN_SRDATA    A0   // PC0 in   74HC166 Q7

// Direct port access (single sbi/cbi/sbis instructions).
#define VSYNC_IS_HIGH() (PIND & _BV(PIND2))
#define MODE_SEND()     (PORTD |= _BV(PORTD6))
#define MODE_CAPTURE()  (PORTD &= ~_BV(PORTD6))
#define ARST_LOW()      (PORTD &= ~_BV(PORTD5))
#define ARST_HIGH()     (PORTD |= _BV(PORTD5))
#define ACLK_HIGH()     (PORTB |= _BV(PORTB0))
#define ACLK_LOW()      (PORTB &= ~_BV(PORTB0))
#define PE_LOW()        (PORTB &= ~_BV(PORTB1))
#define PE_HIGH()       (PORTB |= _BV(PORTB1))
#define SRCLK_HIGH()    (PORTB |= _BV(PORTB2))
#define SRCLK_LOW()     (PORTB &= ~_BV(PORTB2))
#define SRDATA_BIT()    (PINC & _BV(PINC0))
// 4 NOPs = 250 ns at 16 MHz: counter + SRAM settle time after an ACLK edge.
#define NOP4() __asm__ __volatile__("nop\n\tnop\n\tnop\n\tnop\n\t")

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
#define CMD_MAX          159    // max command line length (protocol)
#define MAX_TOKENS       12     // verb + up to 11 arguments
#define SETTLE_MAX_MS    10000
#define MAX_PCLK_KHZ     1100   // CAMTEST timing: above this -> FAIL
#define TIMING_FRAMES    5

// CAMTEST colour-bar check (QQVGA YUYV, see self-test for the reasoning).
#define TEST_MODE        OV7670_MODE_QQVGA_YUYV
#define TEST_LINE_BYTES  (OV7670_MODE_WIDTH(TEST_MODE) * 2)
#define REF_LINE         2      // first lines may be garbage
#define BAR_JUMP         16     // Y/U/V step between pixel pairs = bar edge
#define BARS_MIN_EDGES   5      // 8 bars -> 7 edges expected
#define BARS_MAX_EDGES   9

// Camera state as reported by STATUS.
#define CAM_UNKNOWN 0
#define CAM_OK      1
#define CAM_FAIL    2

// Sub-test results.
#define T_PASS 0
#define T_WARN 1
#define T_FAIL 2

// Per-byte image statistics for the CAMTEST colour-bar check. Defined before
// any function so the Arduino prototype generator can use the type.
struct BarsCheck {
  uint8_t orMask, andMask, first;
  bool allEqual, lineBad;
  uint16_t col, line, badLines;
};

// ---------------------------------------------------------------------------
// Globals
// ---------------------------------------------------------------------------
static bool     g_power;               // camera powered (PWDN low)
static uint8_t  g_cam = CAM_UNKNOWN;
static bool     g_configured;          // CONFIG done since power-up/reset
static uint8_t  g_cfgMode;             // its OV7670_MODE_*
static bool     g_cfgY8;               // send Y bytes only (fmt=y8)
static bool     g_imgValid;            // SRAM holds a complete image
static uint8_t  g_imgMode;             // its OV7670_MODE_*
static bool     g_imgY8;               // it is sent as Y8
static uint32_t g_addr;                // current counter address (SEND)

static char     g_cmd[CMD_MAX + 1];
static uint8_t  g_cmdLen;
static bool     g_cmdTooLong;

static uint8_t  g_regCritical;         // set by onRegMismatch()
static uint8_t  refLine[TEST_LINE_BYTES];

// ---------------------------------------------------------------------------
// Output helpers
// ---------------------------------------------------------------------------
static void nl() {
  Serial.write('\n');
}

static char hexDigit(uint8_t d) {
  return d < 10 ? '0' + d : 'a' + (d - 10);
}

static void printHex2(uint8_t b) {
  Serial.write(hexDigit(b >> 4));
  Serial.write(hexDigit(b & 0x0f));
}

static void kvU32(const __FlashStringHelper *key, uint32_t v) {
  Serial.write(' ');
  Serial.print(key);
  Serial.write('=');
  Serial.print(v);
}

static void kvHex(const __FlashStringHelper *key, uint8_t v) {
  Serial.write(' ');
  Serial.print(key);
  Serial.print(F("=0x"));
  printHex2(v);
}

static void kvStr(const __FlashStringHelper *key,
                  const __FlashStringHelper *v) {
  Serial.write(' ');
  Serial.print(key);
  Serial.write('=');
  Serial.print(v);
}

static void okBegin(const __FlashStringHelper *verb) {
  Serial.print(F("OK "));
  Serial.print(verb);
}

// Complete ERR line; detail may be NULL.
static void err(const __FlashStringHelper *verb,
                const __FlashStringHelper *code, const char *detail) {
  Serial.print(F("ERR "));
  Serial.print(verb);
  Serial.write(' ');
  Serial.print(code);
  if (detail != NULL) {
    Serial.write(' ');
    Serial.print(detail);
  }
  nl();
}

static void testBegin(const __FlashStringHelper *name, uint8_t result) {
  Serial.print(F("TEST "));
  Serial.print(name);
  Serial.print(result == T_PASS ? F(" PASS")
                                : result == T_WARN ? F(" WARN") : F(" FAIL"));
}

// Format as sent to the host. Y8 = the Y bytes of a YUYV capture.
static const __FlashStringHelper *fmtName(uint8_t mode, bool y8) {
  return mode == OV7670_MODE_VGA_BAYER ? F("BAYER") : y8 ? F("Y8") : F("YUYV");
}

// Bytes sent for a whole image.
static uint32_t imageBytes(uint8_t mode, bool y8) {
  return OV7670_MODE_BYTES(mode) / (y8 ? 2 : 1);
}

// ---------------------------------------------------------------------------
// Hardware: pins, counter, 74HC166
// ---------------------------------------------------------------------------
static void initPins() {
  // Level first, then OUTPUT, so no pin glitches. Camera powered down,
  // MODE = SEND.
  digitalWrite(PIN_MODE, HIGH);       pinMode(PIN_MODE, OUTPUT);
  digitalWrite(PIN_PE, HIGH);         pinMode(PIN_PE, OUTPUT);
  digitalWrite(PIN_ARST, HIGH);       pinMode(PIN_ARST, OUTPUT);
  digitalWrite(PIN_ACLK, LOW);        pinMode(PIN_ACLK, OUTPUT);
  digitalWrite(PIN_SRCLK, LOW);       pinMode(PIN_SRCLK, OUTPUT);
  digitalWrite(PIN_CAM_PWDN, HIGH);   pinMode(PIN_CAM_PWDN, OUTPUT);
  digitalWrite(PIN_CAM_RESET, HIGH);  pinMode(PIN_CAM_RESET, OUTPUT);
  pinMode(PIN_VSYNC, INPUT);
  pinMode(PIN_SRDATA, INPUT);
}

static inline void aclkPulse() {
  ACLK_HIGH();
  ACLK_LOW();
  NOP4();
}

// Synchronous clear: /ARST LOW, one ACLK pulse, /ARST HIGH -> address 0.
static void counterClear() {
  ARST_LOW();
  ACLK_HIGH();
  ACLK_LOW();
  ARST_HIGH();
  NOP4();
  g_addr = 0;
}

static void counterSeek(uint32_t addr) {
  if (addr < g_addr) {
    counterClear();
  }
  while (g_addr < addr) {
    aclkPulse();
    g_addr++;
  }
}

// Byte at the current address, MSB first.
static inline uint8_t srReadByte() {
  PE_LOW();
  SRCLK_HIGH();     // rising edge with /PE LOW = parallel load
  SRCLK_LOW();
  PE_HIGH();
  uint8_t v = 0;
  for (uint8_t i = 0; i < 8; i++) {
    v <<= 1;
    if (SRDATA_BIT()) {
      v |= 1;
    }
    SRCLK_HIGH();
    SRCLK_LOW();
  }
  return v;
}

static uint8_t readAt(uint32_t addr) {
  counterSeek(addr);
  return srReadByte();
}

// ---------------------------------------------------------------------------
// VSYNC and snapshot
// ---------------------------------------------------------------------------

// 2 s, or 3 expected frame periods of the mode if that is longer.
static uint16_t vsyncTimeoutMs(uint8_t mode) {
  uint32_t t = 3UL * OV7670_MODE_FRAME_US(mode) / 1000;
  return t > 2000 ? (uint16_t)t : 2000;
}

static bool waitVsyncLevel(bool high, uint16_t timeoutMs) {
  uint32_t t0 = millis();
  while ((VSYNC_IS_HIGH() != 0) != high) {
    if (millis() - t0 > timeoutMs) {
      return false;
    }
  }
  return true;
}

static bool waitVsyncRise(uint16_t timeoutMs) {
  return waitVsyncLevel(false, timeoutMs) && waitVsyncLevel(true, timeoutMs);
}

// MODE=0, let settleFrames complete frames be captured, then switch to
// MODE=1 right at the next VSYNC rising edge: the last complete frame is
// frozen in SRAM. MODE is SEND again on every return path.
static bool snapshot(uint8_t mode, uint8_t settleFrames) {
  uint16_t timeout = vsyncTimeoutMs(mode);
  MODE_CAPTURE();
  // First rise starts the first fully captured frame; settleFrames more
  // rises complete them; the final rise is where MODE switches to SEND.
  for (uint8_t i = 0; i <= settleFrames + 1; i++) {
    if (!waitVsyncRise(timeout)) {
      MODE_SEND();
      return false;
    }
  }
  MODE_SEND();
  return true;
}

// ---------------------------------------------------------------------------
// Camera power / SCCB
// ---------------------------------------------------------------------------
static void cameraResetPulse() {
  digitalWrite(PIN_CAM_RESET, LOW);
  delay(10);
  digitalWrite(PIN_CAM_RESET, HIGH);
  delay(100);
}

// Reads PID/VER; updates g_cam. Returns the Wire status (0 = read OK).
static uint8_t cameraIdCheck(uint8_t *pid, uint8_t *ver) {
  uint8_t s = ov7670_read(REG_PID, pid);
  if (s == 0) {
    s = ov7670_read(REG_VER, ver);
  }
  g_cam = (s == 0 && *pid == 0x76 && *ver == 0x73) ? CAM_OK : CAM_FAIL;
  return s;
}

// ---------------------------------------------------------------------------
// Image streaming
// ---------------------------------------------------------------------------
static void barsCheckInit(BarsCheck *c) {
  c->orMask = 0;
  c->andMask = 0xff;
  c->first = 0;
  c->allEqual = true;
  c->lineBad = false;
  c->col = c->line = c->badLines = 0;
}

static void barsCheckByte(BarsCheck *c, uint32_t k, uint8_t b) {
  c->orMask |= b;
  c->andMask &= b;
  if (k == 0) {
    c->first = b;
  } else if (b != c->first) {
    c->allEqual = false;
  }
  if (c->line == REF_LINE) {
    refLine[c->col] = b;
  } else if (c->line > REF_LINE && b != refLine[c->col] && !c->lineBad) {
    c->lineBad = true;
    c->badLines++;
  }
  if (++c->col == TEST_LINE_BYTES) {
    c->col = 0;
    c->line++;
    c->lineBad = false;
  }
}

// Bar edges on one YUYV line: pixel pairs whose Y0/U/Y1/V differs by more
// than BAR_JUMP from the previous pair; neighbouring edge pairs count once.
static uint8_t countBarEdges(const uint8_t *line) {
  uint8_t edges = 0;
  bool lastWasEdge = false;
  for (uint16_t i = 4; i < TEST_LINE_BYTES; i += 4) {
    bool edge = false;
    for (uint8_t j = 0; j < 4; j++) {
      int16_t d = (int16_t)line[i + j] - (int16_t)line[i + j - 4];
      if (d > BAR_JUMP || d < -BAR_JUMP) {
        edge = true;
      }
    }
    if (edge && !lastWasEdge) {
      edges++;
    }
    lastWasEdge = edge;
  }
  return edges;
}

// Reads len bytes of the stored image starting at offset (both in sent
// bytes; for Y8 only every second SRAM byte, the Y, is sent). If send, emits
// a DATA block (header, raw bytes, END crc16). If chk != NULL, feeds every
// byte to the colour-bar check (offset must be 0, TEST_MODE YUYV image).
static void streamImage(uint32_t offset, uint32_t len, bool send,
                        BarsCheck *chk) {
  if (send) {
    Serial.print(F("DATA"));
    kvU32(F("bytes"), len);
    kvU32(F("offset"), offset);
    kvU32(F("w"), OV7670_MODE_WIDTH(g_imgMode));
    kvU32(F("h"), OV7670_MODE_HEIGHT(g_imgMode));
    kvStr(F("fmt"), fmtName(g_imgMode, g_imgY8));
    nl();
  }
  const uint8_t stride = g_imgY8 ? 2 : 1;   // Y0 U0 Y1 V0: Y at even addresses
  MODE_SEND();
  counterClear();
  counterSeek(offset * stride);
  uint16_t crc = 0;
  for (uint32_t i = 0; i < len; i++) {
    if (i != 0) {
      for (uint8_t k = 0; k < stride; k++) {
        aclkPulse();
      }
      g_addr += stride;
    }
    uint8_t b = srReadByte();
    if (chk != NULL) {
      barsCheckByte(chk, i, b);
    }
    if (send) {
      crc = _crc_xmodem_update(crc, b);
      Serial.write(b);
    }
  }
  if (send) {
    Serial.print(F("END crc16="));
    printHex2(crc >> 8);
    printHex2(crc & 0xff);
    nl();
  }
}

// ---------------------------------------------------------------------------
// Argument parsing
// ---------------------------------------------------------------------------

// Splits "KEY=VALUE" in place; returns VALUE, or NULL if there is no '='.
static char *splitArg(char *tok) {
  char *eq = strchr(tok, '=');
  if (eq == NULL) {
    return NULL;
  }
  *eq = '\0';
  return eq + 1;
}

// Decimal or 0x-hex, whole string.
static bool parseU32(const char *s, uint32_t *out) {
  if (*s == '\0') {
    return false;
  }
  char *end;
  *out = strtoul(s, &end, 0);
  return *end == '\0';
}

// Signed decimal, whole string.
static bool parseI32(const char *s, int32_t *out) {
  if (*s == '\0') {
    return false;
  }
  char *end;
  *out = strtol(s, &end, 10);
  return *end == '\0';
}

// ---------------------------------------------------------------------------
// Commands. tok[0] is the verb, tok[1..n-1] the arguments (all upper case).
// ---------------------------------------------------------------------------

static void cmdStatus(uint8_t n) {
  if (n != 1) {
    err(F("STATUS"), F("BAD_ARG"), NULL);
    return;
  }
  okBegin(F("STATUS"));
  kvStr(F("fw"), F("arducam"));
  kvStr(F("ver"), F(FW_VERSION));
  kvU32(F("proto"), PROTO_VERSION);
  kvStr(F("power"), g_power ? F("on") : F("off"));
  kvStr(F("cam"), g_cam == CAM_OK ? F("ok")
                  : g_cam == CAM_FAIL ? F("fail") : F("unknown"));
  Serial.print(F(" config="));
  if (g_configured) {
    Serial.print(OV7670_MODE_WIDTH(g_cfgMode));
    Serial.write('x');
    Serial.print(OV7670_MODE_HEIGHT(g_cfgMode));
    Serial.write('/');
    Serial.print(fmtName(g_cfgMode, g_cfgY8));
  } else {
    Serial.print(F("none"));
  }
  Serial.print(F(" image="));
  if (g_imgValid) {
    Serial.print(OV7670_MODE_WIDTH(g_imgMode));
    Serial.write('x');
    Serial.print(OV7670_MODE_HEIGHT(g_imgMode));
    Serial.write('/');
    Serial.print(fmtName(g_imgMode, g_imgY8));
  } else {
    Serial.print(F("none"));
  }
  nl();
}

// Prints "OK <verb> [state=on] pid=.. ver=.." or the SCCB error.
static void reportIdCheck(const __FlashStringHelper *verb, bool withState) {
  uint8_t pid = 0, ver = 0;
  if (cameraIdCheck(&pid, &ver) != 0) {
    err(verb, F("SCCB"), NULL);
    return;
  }
  okBegin(verb);
  if (withState) {
    kvStr(F("state"), F("on"));
  }
  kvHex(F("pid"), pid);
  kvHex(F("ver"), ver);
  nl();
}

static void cmdPower(char **tok, uint8_t n) {
  if (n == 1) {
    okBegin(F("POWER"));
    kvStr(F("state"), g_power ? F("on") : F("off"));
    nl();
  } else if (n == 2 && strcmp_P(tok[1], PSTR("ON")) == 0) {
    if (g_power) {
      okBegin(F("POWER"));
      kvStr(F("state"), F("on"));
      nl();
      return;
    }
    digitalWrite(PIN_CAM_PWDN, LOW);
    g_power = true;
    g_configured = false;
    delay(300);
    cameraResetPulse();
    reportIdCheck(F("POWER"), true);
  } else if (n == 2 && strcmp_P(tok[1], PSTR("OFF")) == 0) {
    digitalWrite(PIN_CAM_PWDN, HIGH);
    g_power = false;
    g_configured = false;
    g_cam = CAM_UNKNOWN;
    okBegin(F("POWER"));
    kvStr(F("state"), F("off"));
    nl();
  } else {
    err(F("POWER"), F("BAD_ARG"), n >= 2 ? tok[1] : NULL);
  }
}

static void cmdReset(uint8_t n) {
  if (n != 1) {
    err(F("RESET"), F("BAD_ARG"), NULL);
    return;
  }
  if (!g_power) {
    err(F("RESET"), F("POWER_OFF"), NULL);
    return;
  }
  cameraResetPulse();
  g_configured = false;
  reportIdCheck(F("RESET"), false);
}

static bool allSame(const uint8_t *buf, uint8_t n) {
  for (uint8_t i = 1; i < n; i++) {
    if (buf[i] != buf[0]) {
      return false;
    }
  }
  return true;
}

// Read-only: does not disturb the stored image.
static void cmdMemtest(uint8_t n) {
  if (n != 1) {
    err(F("MEMTEST"), F("BAD_ARG"), NULL);
    return;
  }
  MODE_SEND();
  uint8_t a[16], b[16];
  bool allOk = true;

  // Same address, no counter clocks: data path only.
  counterClear();
  for (uint8_t i = 0; i < 16; i++) {
    a[i] = srReadByte();
  }
  bool ok = allSame(a, 16);
  allOk &= ok;
  testBegin(F("data_path"), ok ? T_PASS : T_FAIL);
  nl();

  // Clear: step away, clear, read -> always the address-0 byte.
  for (uint8_t i = 0; i < 16; i++) {
    for (uint8_t j = 0; j < 37 + i; j++) {
      aclkPulse();
    }
    counterClear();
    a[i] = srReadByte();
  }
  ok = allSame(a, 16);
  allOk &= ok;
  testBegin(F("counter_clear"), ok ? T_PASS : T_FAIL);
  nl();

  // Addresses 0..15 twice.
  counterClear();
  for (uint8_t i = 0; i < 16; i++) {
    a[i] = readAt(i);
  }
  counterClear();
  for (uint8_t i = 0; i < 16; i++) {
    b[i] = readAt(i);
  }
  ok = memcmp(a, b, 16) == 0;
  allOk &= ok;
  testBegin(F("step_short"), ok ? T_PASS : T_FAIL);
  nl();

  // 16x (clear, 1023 steps, read).
  for (uint8_t i = 0; i < 16; i++) {
    counterClear();
    a[i] = readAt(1023);
  }
  ok = allSame(a, 16);
  allOk &= ok;
  testBegin(F("step_long"), ok ? T_PASS : T_FAIL);
  kvU32(F("steps"), 1023);
  nl();

  okBegin(F("MEMTEST"));
  kvStr(F("result"), allOk ? F("pass") : F("fail"));
  nl();
}

static void onRegMismatch(uint8_t reg, uint8_t expected, uint8_t actual,
                          uint8_t mask, uint8_t status, bool critical) {
  if (critical) {
    g_regCritical++;
  }
  Serial.print(F("INFO reg 0x"));
  printHex2(reg);
  if (status != 0) {
    Serial.print(F(" read error "));
    Serial.print(status);
  } else {
    Serial.print(F(" wrote 0x"));
    printHex2(expected);
    Serial.print(F(" read 0x"));
    printHex2(actual);
    Serial.print(F(" mask 0x"));
    printHex2(mask);
  }
  if (critical) {
    Serial.print(F(" critical"));
  }
  nl();
}

// Measures TIMING_FRAMES frames. VSYNC is buffered independently of MODE,
// so the stored image is not touched. False if no VSYNC.
static bool measureTiming(uint8_t mode, uint32_t *frameUs, uint32_t *vsyncUs) {
  uint16_t timeout = vsyncTimeoutMs(mode);
  uint32_t sumP = 0, sumH = 0;
  if (!waitVsyncRise(timeout)) {
    return false;
  }
  uint32_t tRise = micros();
  for (uint8_t i = 0; i < TIMING_FRAMES; i++) {
    if (!waitVsyncLevel(false, timeout)) {
      return false;
    }
    uint32_t tFall = micros();
    if (!waitVsyncLevel(true, timeout)) {
      return false;
    }
    uint32_t t = micros();
    sumH += tFall - tRise;
    sumP += t - tRise;
    tRise = t;
  }
  *frameUs = sumP / TIMING_FRAMES;
  *vsyncUs = sumH / TIMING_FRAMES;
  return true;
}

static void cmdCamtest(char **tok, uint8_t n) {
  bool sendImage = false;
  for (uint8_t i = 1; i < n; i++) {
    char *v = splitArg(tok[i]);
    uint32_t x;
    if (v != NULL && strcmp_P(tok[i], PSTR("IMAGE")) == 0 &&
        parseU32(v, &x) && x <= 1) {
      sendImage = (x == 1);
    } else {
      err(F("CAMTEST"), F("BAD_ARG"), tok[i]);
      return;
    }
  }
  if (!g_power) {
    err(F("CAMTEST"), F("POWER_OFF"), NULL);
    return;
  }
  bool allOk = true;

  // 1. SCCB ID
  uint8_t id[4];
  const uint8_t idRegs[4] = { REG_PID, REG_VER, REG_MIDH, REG_MIDL };
  for (uint8_t i = 0; i < 4; i++) {
    if (ov7670_read(idRegs[i], &id[i]) != 0) {
      g_cam = CAM_FAIL;
      err(F("CAMTEST"), F("SCCB"), NULL);
      return;
    }
  }
  bool ok = id[0] == 0x76 && id[1] == 0x73 && id[2] == 0x7f && id[3] == 0xa2;
  g_cam = ok ? CAM_OK : CAM_FAIL;
  allOk &= ok;
  testBegin(F("sccb_id"), ok ? T_PASS : T_FAIL);
  kvHex(F("pid"), id[0]);
  kvHex(F("ver"), id[1]);
  kvHex(F("midh"), id[2]);
  kvHex(F("midl"), id[3]);
  nl();

  // 2. SCCB write/read-back (BRIGHT is not auto-adjusted)
  uint8_t r1 = 0, r2 = 0;
  ok = ov7670_write(REG_BRIGHT, 0x55) == 0 && ov7670_read(REG_BRIGHT, &r1) == 0 &&
       ov7670_write(REG_BRIGHT, 0xaa) == 0 && ov7670_read(REG_BRIGHT, &r2) == 0 &&
       r1 == 0x55 && r2 == 0xaa;
  ov7670_write(REG_BRIGHT, 0x00);
  allOk &= ok;
  testBegin(F("sccb_rw"), ok ? T_PASS : T_FAIL);
  nl();

  // 3. Register table + read-back
  g_configured = false;   // CAMTEST overwrites the registers
  uint8_t writeErrors = ov7670_configure(TEST_MODE);
  uint16_t checked, skipped;
  g_regCritical = 0;
  uint16_t bad = ov7670_verify_config(onRegMismatch, &checked, &skipped);
  uint8_t res = (writeErrors || g_regCritical) ? T_FAIL : bad ? T_WARN : T_PASS;
  allOk &= res != T_FAIL;
  testBegin(F("registers"), res);
  kvU32(F("checked"), checked);
  kvU32(F("mismatched"), bad);
  kvU32(F("critical"), g_regCritical);
  kvU32(F("write_errors"), writeErrors);
  nl();

  // 4. Timing
  uint32_t frameUs, vsyncUs;
  if (!measureTiming(TEST_MODE, &frameUs, &vsyncUs)) {
    err(F("CAMTEST"), F("NO_VSYNC"), NULL);
    return;
  }
  const uint32_t expPclk = OV7670_MODE_PCLK_KHZ(TEST_MODE);
  uint32_t pclkKhz = expPclk * OV7670_MODE_FRAME_US(TEST_MODE) / frameUs;
  res = pclkKhz > MAX_PCLK_KHZ ? T_FAIL
        : pclkKhz < expPclk * 7 / 10 ? T_WARN : T_PASS;
  allOk &= res != T_FAIL;
  testBegin(F("timing"), res);
  kvU32(F("frame_us"), frameUs);
  kvU32(F("vsync_us"), vsyncUs);
  kvU32(F("pclk_khz"), pclkKhz);
  nl();

  // 5. Colour bars: COM17 DSP colour bar (the variant that works on this
  //    camera), auto exposure/gain/white balance off.
  ov7670_set_auto(false);
  ov7670_set_dsp_colour_bar(true);
  g_imgValid = false;
  if (!snapshot(TEST_MODE, 2)) {
    err(F("CAMTEST"), F("NO_VSYNC"), NULL);
    return;
  }
  g_imgValid = true;
  g_imgMode = TEST_MODE;
  g_imgY8 = false;
  BarsCheck chk;
  barsCheckInit(&chk);
  streamImage(0, OV7670_MODE_BYTES(TEST_MODE), sendImage, &chk);
  uint8_t edges = countBarEdges(refLine);
  uint8_t neverOne = ~chk.orMask, neverZero = chk.andMask;
  ok = !chk.allEqual && chk.badLines == 0 && neverOne == 0 &&
       neverZero == 0 && edges >= BARS_MIN_EDGES && edges <= BARS_MAX_EDGES;
  allOk &= ok;
  testBegin(F("bars"), ok ? T_PASS : T_FAIL);
  kvU32(F("edges"), edges);
  kvU32(F("bad_lines"), chk.badLines);
  kvHex(F("never1"), neverOne);
  kvHex(F("never0"), neverZero);
  nl();

  okBegin(F("CAMTEST"));
  kvStr(F("result"), allOk ? F("pass") : F("fail"));
  nl();
}

// Writes the full register configuration for a mode (starts with a
// register reset, so nothing from a previous CONFIG/CAMTEST survives).
// Auto exposure/gain/white balance start from zero: the host should settle
// before the first PICTURE.
static void cmdConfig(char **tok, uint8_t n) {
  uint8_t fmt = 0;        // 0 = YUYV, 1 = BAYER, 2 = Y8
  uint8_t res = 1;        // 0 = QQVGA, 1 = QVGA, 2 = VGA
  uint8_t effect = OV7670_EFFECT_NONE;
  bool bars = false, autoOn = true;
  // Adjustments are applied only when given (otherwise camera defaults).
  bool hasBright = false, hasContrast = false, hasSat = false;
  bool hasExp = false, hasGain = false, hasEffect = false;
  int32_t bright = 0;
  uint32_t contrast = 0, sat = 0, exposure = 0, gain = 0;

  for (uint8_t i = 1; i < n; i++) {
    char *v = splitArg(tok[i]);
    bool ok = v != NULL;
    if (!ok) {
      // no '=': bad argument
    } else if (strcmp_P(tok[i], PSTR("FMT")) == 0) {
      if (strcmp_P(v, PSTR("YUYV")) == 0) fmt = 0;
      else if (strcmp_P(v, PSTR("BAYER")) == 0) fmt = 1;
      else if (strcmp_P(v, PSTR("Y8")) == 0) fmt = 2;
      else ok = false;
    } else if (strcmp_P(tok[i], PSTR("RES")) == 0) {
      if (strcmp_P(v, PSTR("QQVGA")) == 0) res = 0;
      else if (strcmp_P(v, PSTR("QVGA")) == 0) res = 1;
      else if (strcmp_P(v, PSTR("VGA")) == 0) res = 2;
      else ok = false;
    } else if (strcmp_P(tok[i], PSTR("PATTERN")) == 0) {
      if (strcmp_P(v, PSTR("NONE")) == 0) bars = false;
      else if (strcmp_P(v, PSTR("BARS")) == 0) bars = true;
      else ok = false;
    } else if (strcmp_P(tok[i], PSTR("AUTO")) == 0) {
      if (strcmp_P(v, PSTR("ON")) == 0) autoOn = true;
      else if (strcmp_P(v, PSTR("OFF")) == 0) autoOn = false;
      else ok = false;
    } else if (strcmp_P(tok[i], PSTR("EFFECT")) == 0) {
      hasEffect = true;
      if (strcmp_P(v, PSTR("NONE")) == 0) effect = OV7670_EFFECT_NONE;
      else if (strcmp_P(v, PSTR("MONO")) == 0) effect = OV7670_EFFECT_MONO;
      else if (strcmp_P(v, PSTR("NEGATIVE")) == 0) effect = OV7670_EFFECT_NEGATIVE;
      else if (strcmp_P(v, PSTR("SEPIA")) == 0) effect = OV7670_EFFECT_SEPIA;
      else ok = false;
    } else if (strcmp_P(tok[i], PSTR("BRIGHTNESS")) == 0) {
      hasBright = true;
      ok = parseI32(v, &bright) && bright >= -127 && bright <= 127;
    } else if (strcmp_P(tok[i], PSTR("CONTRAST")) == 0) {
      hasContrast = true;
      ok = parseU32(v, &contrast) && contrast <= 255;
    } else if (strcmp_P(tok[i], PSTR("SATURATION")) == 0) {
      hasSat = true;
      ok = parseU32(v, &sat) && sat <= 255;
    } else if (strcmp_P(tok[i], PSTR("EXPOSURE")) == 0) {
      hasExp = true;
      ok = parseU32(v, &exposure) && exposure <= 65535;
    } else if (strcmp_P(tok[i], PSTR("GAIN")) == 0) {
      hasGain = true;
      ok = parseU32(v, &gain) && gain <= 255;
    } else {
      ok = false;
    }
    if (!ok) {
      err(F("CONFIG"), F("BAD_ARG"), tok[i]);
      return;
    }
  }

  uint8_t mode;
  if (fmt != 1 && res == 0) {
    mode = OV7670_MODE_QQVGA_YUYV;
  } else if (fmt != 1 && res == 1) {
    mode = OV7670_MODE_QVGA_YUYV;
  } else if (fmt == 1 && res == 2) {
    mode = OV7670_MODE_VGA_BAYER;
  } else {
    err(F("CONFIG"), F("BAD_ARG"), "unsupported fmt/res combination");
    return;
  }
  // Effects and image adjustments are DSP functions: not in raw Bayer.
  if (fmt == 1 && (hasEffect || hasBright || hasContrast || hasSat)) {
    err(F("CONFIG"), F("BAD_ARG"), "effect/brightness/contrast/saturation need fmt=yuyv or y8");
    return;
  }
  if (autoOn && (hasExp || hasGain)) {
    err(F("CONFIG"), F("BAD_ARG"), "exposure/gain need auto=off");
    return;
  }
  if (!g_power) {
    err(F("CONFIG"), F("POWER_OFF"), NULL);
    return;
  }

  g_configured = false;
  uint8_t s = ov7670_configure(mode);
  if (s == 0 && bars) {
    s = ov7670_set_dsp_colour_bar(true);
  }
  if (s == 0) {
    s = ov7670_set_auto(autoOn);
  }
  if (s == 0 && hasEffect) {
    s = ov7670_set_effect(effect);
  }
  if (s == 0 && hasBright) {
    s = ov7670_set_brightness((int8_t)bright);
  }
  if (s == 0 && hasContrast) {
    s = ov7670_set_contrast((uint8_t)contrast);
  }
  if (s == 0 && hasSat) {
    s = ov7670_set_saturation((uint8_t)sat);
  }
  if (s == 0 && hasExp) {
    s = ov7670_set_exposure((uint16_t)exposure);
  }
  if (s == 0 && hasGain) {
    s = ov7670_set_gain((uint8_t)gain);
  }
  if (s != 0) {
    g_cam = CAM_FAIL;
    err(F("CONFIG"), F("SCCB"), NULL);
    return;
  }
  g_configured = true;
  g_cfgMode = mode;
  g_cfgY8 = (fmt == 2);
  okBegin(F("CONFIG"));
  kvU32(F("w"), OV7670_MODE_WIDTH(mode));
  kvU32(F("h"), OV7670_MODE_HEIGHT(mode));
  kvStr(F("fmt"), fmtName(mode, g_cfgY8));
  kvU32(F("bytes"), imageBytes(mode, g_cfgY8));
  nl();
}

// Captures one frame with the current configuration (no register writes,
// so auto exposure keeps its converged state between pictures).
static void cmdPicture(char **tok, uint8_t n) {
  bool send = true;
  uint32_t settle = 0;

  for (uint8_t i = 1; i < n; i++) {
    char *v = splitArg(tok[i]);
    uint32_t x;
    bool ok = false;
    if (v != NULL && strcmp_P(tok[i], PSTR("SETTLE")) == 0) {
      ok = parseU32(v, &settle) && settle <= SETTLE_MAX_MS;
    } else if (v != NULL && strcmp_P(tok[i], PSTR("SEND")) == 0) {
      ok = parseU32(v, &x) && x <= 1;
      send = (x == 1);
    }
    if (!ok) {
      err(F("PICTURE"), F("BAD_ARG"), tok[i]);
      return;
    }
  }
  if (!g_power) {
    err(F("PICTURE"), F("POWER_OFF"), NULL);
    return;
  }
  if (!g_configured) {
    err(F("PICTURE"), F("NOT_CONFIGURED"), NULL);
    return;
  }
  uint8_t mode = g_cfgMode;
  bool y8 = g_cfgY8;

  if (settle != 0) {
    Serial.print(F("INFO settling "));
    Serial.print(settle);
    Serial.print(F(" ms"));
    nl();
    delay(settle);
  }

  g_imgValid = false;   // SRAM is overwritten from here on
  if (!snapshot(mode, 0)) {
    err(F("PICTURE"), F("NO_VSYNC"), NULL);
    return;
  }
  g_imgValid = true;
  g_imgMode = mode;
  g_imgY8 = y8;
  uint32_t bytes = imageBytes(mode, y8);
  if (send) {
    streamImage(0, bytes, true, NULL);
  }
  okBegin(F("PICTURE"));
  kvU32(F("w"), OV7670_MODE_WIDTH(mode));
  kvU32(F("h"), OV7670_MODE_HEIGHT(mode));
  kvStr(F("fmt"), fmtName(mode, y8));
  kvU32(F("bytes"), bytes);
  nl();
}

static void cmdRead(char **tok, uint8_t n) {
  uint32_t offset = 0, len = 0;
  bool haveLen = false;
  for (uint8_t i = 1; i < n; i++) {
    char *v = splitArg(tok[i]);
    bool ok = false;
    if (v != NULL && strcmp_P(tok[i], PSTR("OFFSET")) == 0) {
      ok = parseU32(v, &offset);
    } else if (v != NULL && strcmp_P(tok[i], PSTR("LEN")) == 0) {
      ok = parseU32(v, &len);
      haveLen = true;
    }
    if (!ok) {
      err(F("READ"), F("BAD_ARG"), tok[i]);
      return;
    }
  }
  if (!g_imgValid) {
    err(F("READ"), F("NO_IMAGE"), NULL);
    return;
  }
  uint32_t total = imageBytes(g_imgMode, g_imgY8);
  if (!haveLen && offset < total) {
    len = total - offset;
  }
  if (offset >= total || len == 0 || len > total - offset) {
    err(F("READ"), F("BAD_ARG"), "range outside the image");
    return;
  }
  streamImage(offset, len, true, NULL);
  okBegin(F("READ"));
  nl();
}

// ---------------------------------------------------------------------------
// Command line handling
// ---------------------------------------------------------------------------
static void processLine() {
  char *tok[MAX_TOKENS];
  uint8_t n = 0;
  char *p = g_cmd;
  while (*p != '\0') {
    while (*p == ' ') {
      p++;
    }
    if (*p == '\0') {
      break;
    }
    if (n == MAX_TOKENS) {
      Serial.print(F("ERR "));
      Serial.print(tok[0]);
      Serial.print(F(" BAD_ARG too many arguments"));
      nl();
      return;
    }
    tok[n++] = p;
    while (*p != '\0' && *p != ' ') {
      p++;
    }
    if (*p != '\0') {
      *p++ = '\0';
    }
  }
  if (n == 0) {
    return;   // empty line
  }

  if (strcmp_P(tok[0], PSTR("STATUS")) == 0) {
    cmdStatus(n);
  } else if (strcmp_P(tok[0], PSTR("POWER")) == 0) {
    cmdPower(tok, n);
  } else if (strcmp_P(tok[0], PSTR("RESET")) == 0) {
    cmdReset(n);
  } else if (strcmp_P(tok[0], PSTR("MEMTEST")) == 0) {
    cmdMemtest(n);
  } else if (strcmp_P(tok[0], PSTR("CAMTEST")) == 0) {
    cmdCamtest(tok, n);
  } else if (strcmp_P(tok[0], PSTR("CONFIG")) == 0) {
    cmdConfig(tok, n);
  } else if (strcmp_P(tok[0], PSTR("PICTURE")) == 0) {
    cmdPicture(tok, n);
  } else if (strcmp_P(tok[0], PSTR("READ")) == 0) {
    cmdRead(tok, n);
  } else {
    err(F("?"), F("UNKNOWN_CMD"), tok[0]);
  }
}

void setup() {
  initPins();
  Serial.begin(BAUD);
  Wire.begin();
  Wire.setClock(100000);
  Wire.setWireTimeout(25000, true);   // never hang on a stuck SCCB bus

  Serial.print(F("BOOT"));
  kvStr(F("fw"), F("arducam"));
  kvStr(F("ver"), F(FW_VERSION));
  kvU32(F("proto"), PROTO_VERSION);
  nl();
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') {
      continue;
    }
    if (c == '\n') {
      if (g_cmdTooLong) {
        err(F("?"), F("TOO_LONG"), NULL);
      } else {
        g_cmd[g_cmdLen] = '\0';
        processLine();
      }
      g_cmdLen = 0;
      g_cmdTooLong = false;
    } else if (g_cmdLen < CMD_MAX) {
      g_cmd[g_cmdLen++] = toupper((unsigned char)c);
    } else {
      g_cmdTooLong = true;
    }
  }
}
