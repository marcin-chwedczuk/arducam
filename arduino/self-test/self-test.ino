// self-test.ino — OV7670 snapshot camera: hardware bring-up / test firmware.
//
// Board: Arduino Uno (ATmega328P, 16 MHz), uploaded with Atmel-ICE via ISP
// ("Upload Using Programmer"). Serial via an external USB-serial module on
// D0/D1.
//
// Data path: OV7670 (QVGA or QQVGA YUYV, PCLK 500 kHz) -> hardware 19-bit address
// counter (5x 74HC163) -> AS6C4008 SRAM. In SEND mode the Arduino steps the
// counter (ACLK) and reads SRAM bytes through a 74HC166 shift register.
//
// Stages (run after a key is pressed):
//   0 init, 1 memory/counter read test, 2 camera bring-up + SCCB tests,
//   3 VSYNC timing, 4 colour-bar test image.
// Then a command loop: b = colour bars, r = real image, m = memory test,
// t = timing test, h = help.
//
// Serial protocol: status lines start with '#'; data lines are lowercase hex,
// 16 bytes per line, '\n' terminated. Memory-test lines end with '.'.
//
// The OV7670 register tables and SCCB helpers live in ov7670.h / ov7670.cpp.

#include <Wire.h>
#include "ov7670.h"

#define BAUD 250000   // exact on 16 MHz AVR (also 500000, 1000000)

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

// Direct port access for the time-critical paths. These compile to single
// sbi/cbi/sbis instructions, so other bits of the ports are not disturbed.
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
// Image / test parameters
// ---------------------------------------------------------------------------
#define IMG_W            OV7670_WIDTH                       // see ov7670.h
#define IMG_H            OV7670_HEIGHT
#define IMG_LINE_BYTES   (IMG_W * 2)                        // YUYV: 2 B/pixel
#define IMG_BYTES        ((uint32_t)IMG_LINE_BYTES * IMG_H) // 153600 / 38400
static_assert(IMG_BYTES % 16 == 0, "image must be a whole number of hex lines");

// Expected camera timing. Internal clock = XCLK / OV7670_CLK_DIV. One frame =
// 510 lines x 784 pixel periods x 2 internal clocks (YUV, 2 bytes/pixel);
// VSYNC = 3 lines. PCLK = internal clock / OV7670_PCLK_DIV (DCW scaling).
#define INT_KHZ          (OV7670_XCLK_HZ / OV7670_CLK_DIV / 1000)
#define EXP_PCLK_KHZ     (INT_KHZ / OV7670_PCLK_DIV)
#define EXP_PERIOD_US    (799680UL * 1000UL / INT_KHZ)  // 510 * 784 * 2
#define EXP_VSYNC_US     (4704UL * 1000UL / INT_KHZ)    // 3 * 784 * 2
// VSYNC wait timeout: 2 s, or 3 expected frame periods if that is longer.
#define VSYNC_TIMEOUT_MS ((3UL * EXP_PERIOD_US / 1000) > 2000 ? \
                          (3UL * EXP_PERIOD_US / 1000) : 2000UL)

#define MAX_PCLK_KHZ     1100   // above this the breadboard counter is at risk
// Bar edge: |dY|, |dU| or |dV| > BAR_JUMP between neighbouring pixel pairs.
// (The spec said Y > 32, but full-range 8-bar Y steps are only ~29/47/29/45,
// so 32 would miss about half of the edges; chroma steps are large.)
#define BAR_JUMP         16
#define BARS_MIN_EDGES   5      // 8 bars -> 7 edges expected
#define BARS_MAX_EDGES   9
#define NO_IMAGE         0xff   // returned by image functions when no capture
// The first scaled lines may be garbage (LiveOV7670), so line 2 is the
// reference for the line consistency check and the bar count; lines 0..1
// are only reported as INFO.
#define REF_LINE         2

// Colour-bar modes: 0..3 = SCALING_XSC/YSC test pattern (kernel encoding,
// OV7670_PATTERN_*), BAR_MODE_COM17 = DSP colour bar (COM17[3]).
#define BAR_MODE_COM17   4

// Status line kinds (plain defines, not an enum: the Arduino prototype
// generator would place prototypes before an enum declaration).
#define S_INFO 0
#define S_PASS 1
#define S_WARN 2
#define S_FAIL 3

// ---------------------------------------------------------------------------
// Globals
// ---------------------------------------------------------------------------
static uint16_t g_count[4];            // number of INFO/PASS/WARN/FAIL lines
static uint32_t g_addr;                // current SRAM counter address (SEND)
static uint8_t  g_barMode = OV7670_PATTERN_BARS;  // XSC.7=0, YSC.7=1
static uint8_t  g_criticalMismatches;  // set by onRegMismatch()
static uint8_t  refLine[IMG_LINE_BYTES];  // image line REF_LINE

// ---------------------------------------------------------------------------
// Serial output helpers
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

static void printHexByte(uint8_t b) {
  Serial.print(F("0x"));
  printHex2(b);
}

// Starts a status line: "# PASS: " etc.
static void st(uint8_t kind) {
  g_count[kind]++;
  switch (kind) {
    case S_PASS: Serial.print(F("# PASS: ")); break;
    case S_WARN: Serial.print(F("# WARN: ")); break;
    case S_FAIL: Serial.print(F("# FAIL: ")); break;
    default:     Serial.print(F("# INFO: ")); break;
  }
}

// Complete status line with a constant message.
static void stln(uint8_t kind, const __FlashStringHelper *msg) {
  st(kind);
  Serial.print(msg);
  nl();
}

static void stageHeader(uint8_t n, const __FlashStringHelper *title) {
  Serial.print(F("# STAGE "));
  Serial.print(n);
  Serial.print(F(": "));
  Serial.print(title);
  nl();
}

// One data line: up to 16 bytes, "xx xx ... xx" [+ '.'] + '\n'.
static void printHexLine(const uint8_t *buf, uint8_t n, bool dot) {
  char out[16 * 3 + 2];
  uint8_t p = 0;
  for (uint8_t i = 0; i < n; i++) {
    if (i != 0) {
      out[p++] = ' ';
    }
    out[p++] = hexDigit(buf[i] >> 4);
    out[p++] = hexDigit(buf[i] & 0x0f);
  }
  if (dot) {
    out[p++] = '.';
  }
  out[p++] = '\n';
  Serial.write((const uint8_t *)out, p);
}

static void printWireError(uint8_t status) {
  Serial.print(F("Wire error "));
  Serial.print(status);
  switch (status) {
    case 2: Serial.print(F(" (address NACK: power, reset, PWDN, wiring?)")); break;
    case 3: Serial.print(F(" (data NACK)")); break;
    case 5: Serial.print(F(" (timeout: SDA/SCL stuck?)")); break;
    case OV7670_ERR_NODATA: Serial.print(F(" (no data returned)")); break;
    default: break;
  }
}

// ---------------------------------------------------------------------------
// SRAM readout: address counter + 74HC166 (SEND mode only)
// ---------------------------------------------------------------------------

// One counter clock: rising edge = address + 1. Then wait for the counter
// and SRAM to settle before the 166 may load.
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

// Step the counter forward to `addr` (clears first if addr is behind).
static void counterSeek(uint32_t addr) {
  if (addr < g_addr) {
    counterClear();
  }
  while (g_addr < addr) {
    aclkPulse();
    g_addr++;
  }
}

// Read the byte at the current address through the 74HC166, MSB first.
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
    SRCLK_HIGH();   // shift next bit into Q7
    SRCLK_LOW();
  }
  return v;
}

static uint8_t readAt(uint32_t addr) {
  counterSeek(addr);
  return srReadByte();
}

// ---------------------------------------------------------------------------
// VSYNC helpers
// ---------------------------------------------------------------------------

// Waits until VSYNC has the given level; false on timeout.
static bool waitVsyncLevel(bool high) {
  uint32_t t0 = millis();
  while ((VSYNC_IS_HIGH() != 0) != high) {
    if (millis() - t0 > VSYNC_TIMEOUT_MS) {
      return false;
    }
  }
  return true;
}

static bool waitVsyncRise() {
  return waitVsyncLevel(false) && waitVsyncLevel(true);
}

// Snapshot: MODE=0, let settleFrames complete frames be captured, then at the
// next VSYNC rising edge switch to MODE=1 (the window is the VSYNC pulse plus
// the blank lines, ~8 ms). The last complete frame is then frozen in SRAM.
static bool snapshot(uint8_t settleFrames) {
  MODE_CAPTURE();
  // The first rising edge starts the first frame captured completely.
  for (uint8_t i = 0; i <= settleFrames; i++) {
    if (!waitVsyncRise()) {
      MODE_SEND();
      stln(S_FAIL, F("no VSYNC"));
      return false;
    }
  }
  if (!waitVsyncRise()) {
    MODE_SEND();
    stln(S_FAIL, F("no VSYNC"));
    return false;
  }
  MODE_SEND();  // immediately after the rising edge
  return true;
}

// ---------------------------------------------------------------------------
// Stage 0: pin init
// ---------------------------------------------------------------------------
static void initPins() {
  // Order matters. For outputs the level is set before switching to OUTPUT,
  // so a pin never glitches to the wrong level.
  digitalWrite(PIN_MODE, HIGH);       pinMode(PIN_MODE, OUTPUT);      // SEND
  digitalWrite(PIN_PE, HIGH);         pinMode(PIN_PE, OUTPUT);
  digitalWrite(PIN_ARST, HIGH);       pinMode(PIN_ARST, OUTPUT);
  digitalWrite(PIN_ACLK, LOW);        pinMode(PIN_ACLK, OUTPUT);
  digitalWrite(PIN_SRCLK, LOW);       pinMode(PIN_SRCLK, OUTPUT);
  digitalWrite(PIN_CAM_PWDN, HIGH);   pinMode(PIN_CAM_PWDN, OUTPUT);  // off
  digitalWrite(PIN_CAM_RESET, HIGH);  pinMode(PIN_CAM_RESET, OUTPUT);
  pinMode(PIN_VSYNC, INPUT);
  pinMode(PIN_SRDATA, INPUT);
  // D11..D13 are left untouched (inputs) for the ISP programmer.
}

// ---------------------------------------------------------------------------
// Stage 1: memory + counter read test
// ---------------------------------------------------------------------------
static void memoryTest() {
  MODE_SEND();
  uint8_t d[3][16];
  for (uint8_t p = 0; p < 3; p++) {
    counterClear();
    for (uint8_t i = 0; i < 16; i++) {
      d[p][i] = readAt(64UL * i + 3);
    }
    printHexLine(d[p], 16, true);
  }

  // Repeatability
  uint16_t diffMask = 0;
  for (uint8_t i = 0; i < 16; i++) {
    if (d[0][i] != d[1][i] || d[0][i] != d[2][i]) {
      diffMask |= (1u << i);
    }
  }
  if (diffMask == 0) {
    stln(S_PASS, F("memory reads repeatable"));
  } else {
    st(S_FAIL);
    Serial.print(F("memory reads differ between passes at positions:"));
    for (uint8_t i = 0; i < 16; i++) {
      if (diffMask & (1u << i)) {
        Serial.write(' ');
        Serial.print(i);
      }
    }
    nl();
    stln(S_INFO, F("run 'd' for read-path diagnostics"));
  }

  // Stuck bits
  uint8_t orMask = 0, andMask = 0xff;
  bool allSame = true;
  for (uint8_t p = 0; p < 3; p++) {
    for (uint8_t i = 0; i < 16; i++) {
      orMask |= d[p][i];
      andMask &= d[p][i];
      if (d[p][i] != d[0][0]) {
        allSame = false;
      }
    }
  }
  bool warned = false;
  if (allSame) {
    st(S_WARN);
    Serial.print(F("all 48 bytes are "));
    printHexByte(d[0][0]);
    Serial.print(F(" (SRAM content should be random after power-up)"));
    nl();
    warned = true;
  }
  if ((uint8_t)~orMask != 0) {
    st(S_WARN);
    Serial.print(F("data bits never 1: mask "));
    printHexByte(~orMask);
    nl();
    warned = true;
  }
  if (andMask != 0) {
    st(S_WARN);
    Serial.print(F("data bits never 0: mask "));
    printHexByte(andMask);
    nl();
    warned = true;
  }
  if (!warned) {
    stln(S_PASS, F("no stuck data bits in 48 bytes"));
  }
}

// ---------------------------------------------------------------------------
// 'd': read-path diagnostics (SEND mode). Separates the data path (SRAM
// outputs + 74HC166) from counter clear and counter stepping.
// ---------------------------------------------------------------------------
static bool allSame(const uint8_t *buf, uint8_t n) {
  for (uint8_t i = 1; i < n; i++) {
    if (buf[i] != buf[0]) {
      return false;
    }
  }
  return true;
}

static void readPathDiag() {
  MODE_SEND();
  uint8_t a[16], b[16];

  // 1. Same address, no counter clocks: only the data path is exercised.
  counterClear();
  for (uint8_t i = 0; i < 16; i++) {
    a[i] = srReadByte();
  }
  stln(S_INFO, F("test 1: 16 reads of one address, no counter clocks"));
  printHexLine(a, 16, true);
  bool dataOk = allSame(a, 16);
  stln(dataOk ? S_PASS : S_FAIL,
       dataOk ? F("data path stable")
              : F("data path unstable: SRAM outputs / OE# / U8 disable / 74HC166"));

  // 2. Counter clear: move away from 0, clear, read. Repeated 16 times.
  for (uint8_t i = 0; i < 16; i++) {
    for (uint8_t j = 0; j < 37 + i; j++) {
      aclkPulse();
    }
    counterClear();
    a[i] = srReadByte();
  }
  stln(S_INFO, F("test 2: 16x (step 37+ addresses away, clear, read)"));
  printHexLine(a, 16, true);
  bool clearOk = allSame(a, 16);
  stln(clearOk ? S_PASS : S_FAIL,
       clearOk ? F("counter clear works")
               : F("counter clear does not return to the same address: /ARST (D5) -> 74HC157 -> MR"));

  // 3. Short sequential read 0..15, twice.
  counterClear();
  for (uint8_t i = 0; i < 16; i++) {
    a[i] = readAt(i);
  }
  counterClear();
  for (uint8_t i = 0; i < 16; i++) {
    b[i] = readAt(i);
  }
  stln(S_INFO, F("test 3: addresses 0..15, two passes"));
  printHexLine(a, 16, true);
  printHexLine(b, 16, true);
  bool seqOk = memcmp(a, b, 16) == 0;
  stln(seqOk ? S_PASS : S_FAIL,
       seqOk ? F("short sequential reads repeatable")
             : F("short sequential reads differ: ACLK (D8) -> 74HC157 -> CP miscounts"));

  // 4. Long stepping: 16x (clear, step to 1023, read).
  for (uint8_t i = 0; i < 16; i++) {
    counterClear();
    a[i] = readAt(1023);
  }
  stln(S_INFO, F("test 4: 16x (clear, step 1023 clocks, read)"));
  printHexLine(a, 16, true);
  bool longOk = allSame(a, 16);
  stln(longOk ? S_PASS : S_FAIL,
       longOk ? F("1023 counter steps exact")
              : F("long stepping not exact: occasional miscounts (ACLK ringing / carry chain?)"));

  // 5. Slow clear: /ARST LOW for ~70 us with 3 slow ACLK pulses. If this
  //    works but test 2 does not, the fast clear violates timing; if it
  //    also fails, /ARST does not reach MR (wiring).
  for (uint8_t i = 0; i < 16; i++) {
    for (uint8_t j = 0; j < 37 + i; j++) {
      aclkPulse();
    }
    ARST_LOW();
    delayMicroseconds(10);
    for (uint8_t j = 0; j < 3; j++) {
      ACLK_HIGH();
      delayMicroseconds(10);
      ACLK_LOW();
      delayMicroseconds(10);
    }
    ARST_HIGH();
    delayMicroseconds(10);
    a[i] = srReadByte();
  }
  g_addr = 0;
  stln(S_INFO, F("test 5: 16x (step away, slow clear, read)"));
  printHexLine(a, 16, true);
  bool slowOk = allSame(a, 16);
  if (slowOk && !clearOk) {
    stln(S_WARN, F("slow clear works but fast clear does not: timing (/ARST vs ACLK)"));
  } else if (slowOk) {
    stln(S_PASS, F("slow clear works"));
  } else {
    stln(S_FAIL, F("slow clear fails too: /ARST does not reach MR (use 'p' to probe)"));
  }
}

// 'p': hold /ARST LOW (SEND mode) for 60 s so the clear path can be
// measured with a multimeter.
static void probeClear() {
  MODE_SEND();
  stln(S_INFO, F("holding MODE=1 and /ARST (D5) LOW for 60 s. Expect LOW at: "
                 "74HC157 pin 3 (I1a), pin 4 (Za), pin 1 (MR) of all five "
                 "74HC163; HIGH at 74HC157 pin 1 (S); LOW at pin 15 (E)"));
  ARST_LOW();
  delay(60000);
  ARST_HIGH();
  counterClear();
  stln(S_INFO, F("/ARST released"));
}

// ---------------------------------------------------------------------------
// Stage 2: camera bring-up and SCCB tests
// ---------------------------------------------------------------------------
// Returns the Wire status of the read (0 = read OK, value may still differ).
static uint8_t checkId(const __FlashStringHelper *name, uint8_t reg,
                       uint8_t expected) {
  uint8_t v = 0;
  uint8_t s = ov7670_read(reg, &v);
  if (s != 0) {
    st(S_FAIL);
    Serial.print(name);
    Serial.print(F(" ("));
    printHexByte(reg);
    Serial.print(F(") read failed: "));
    printWireError(s);
    nl();
    return s;
  }
  st(v == expected ? S_PASS : S_FAIL);
  Serial.print(name);
  Serial.print(F(" ("));
  printHexByte(reg);
  Serial.print(F(") = "));
  printHexByte(v);
  if (v != expected) {
    Serial.print(F(", expected "));
    printHexByte(expected);
  }
  nl();
  return 0;
}

static bool brightTest(uint8_t val) {
  uint8_t r = 0;
  uint8_t s = ov7670_write(REG_BRIGHT, val);
  if (s == 0) {
    s = ov7670_read(REG_BRIGHT, &r);
  }
  if (s != 0) {
    st(S_FAIL);
    Serial.print(F("BRIGHT write/read "));
    printHexByte(val);
    Serial.print(F(": "));
    printWireError(s);
    nl();
    return false;
  }
  st(r == val ? S_PASS : S_FAIL);
  Serial.print(F("BRIGHT (0x55) wrote "));
  printHexByte(val);
  Serial.print(F(", read "));
  printHexByte(r);
  nl();
  return r == val;
}

// Callback from ov7670_verify_config() for each register that differs.
static void onRegMismatch(uint8_t reg, uint8_t expected, uint8_t actual,
                          uint8_t mask, uint8_t status, bool critical) {
  if (critical) {
    g_criticalMismatches++;
  }
  st(critical ? S_FAIL : S_WARN);
  Serial.print(F("reg "));
  printHexByte(reg);
  if (status != 0) {
    Serial.print(F(" read failed: "));
    printWireError(status);
  } else {
    Serial.print(F(" wrote "));
    printHexByte(expected);
    Serial.print(F(", read "));
    printHexByte(actual);
    if (mask != 0xff) {
      Serial.print(F(" (compared bits "));
      printHexByte(mask);
      Serial.write(')');
    }
  }
  nl();
}

static void cameraBringUp() {
  // Power up and hardware reset
  digitalWrite(PIN_CAM_PWDN, LOW);
  delay(300);
  digitalWrite(PIN_CAM_RESET, LOW);
  delay(10);
  digitalWrite(PIN_CAM_RESET, HIGH);
  delay(100);
  stln(S_INFO, F("camera powered up and reset"));

  Wire.begin();
  Wire.setClock(100000);
  // 25 ms timeout with bus reset; avoids hanging on a stuck bus.
  // (Available since AVR core 1.8.3; 1.8.6 does not define the
  // WIRE_HAS_TIMEOUT macro, so it is called unconditionally.)
  Wire.setWireTimeout(25000, true);

  // ID check. If the camera does not answer at all, skip the rest of the
  // SCCB tests instead of printing ~185 read errors.
  if (checkId(F("PID"), REG_PID, 0x76) != 0) {
    stln(S_FAIL, F("camera not responding on SCCB: skipping register setup"));
    return;
  }
  checkId(F("VER"),  REG_VER,  0x73);
  checkId(F("MIDH"), REG_MIDH, 0x7f);
  checkId(F("MIDL"), REG_MIDL, 0xa2);

  // Software reset
  uint8_t s = ov7670_write(REG_COM7, COM7_RESET);
  delay(100);
  if (s != 0) {
    st(S_FAIL);
    Serial.print(F("software reset (COM7 = 0x80): "));
    printWireError(s);
    nl();
  } else {
    stln(S_PASS, F("software reset (COM7 = 0x80)"));
  }

  // Write/read-back test on BRIGHT (not auto-adjusted)
  brightTest(0x55);
  brightTest(0xaa);
  ov7670_write(REG_BRIGHT, 0x00);

  // Full register table
  uint8_t failed = ov7670_configure_yuyv();
  if (failed != 0) {
    st(S_FAIL);
    Serial.print(failed);
    Serial.print(F(" SCCB writes failed while writing the register table"));
    nl();
  } else {
    st(S_PASS);
    Serial.print(F("register table written (" OV7670_SIZE_NAME " "));
    Serial.print(IMG_W);
    Serial.write('x');
    Serial.print(IMG_H);
    Serial.print(F(" YUYV)"));
    nl();
  }

  // Read back
  uint16_t checked, skipped;
  g_criticalMismatches = 0;
  uint16_t bad = ov7670_verify_config(onRegMismatch, &checked, &skipped);
  st(bad == 0 ? S_PASS : (g_criticalMismatches ? S_FAIL : S_WARN));
  if (bad == 0) {
    Serial.print(F("all "));
    Serial.print(checked);
    Serial.print(F(" registers read back OK"));
  } else {
    Serial.print(bad);
    Serial.print(F(" of "));
    Serial.print(checked);
    Serial.print(F(" registers differ ("));
    Serial.print(g_criticalMismatches);
    Serial.print(F(" critical)"));
  }
  Serial.print(F("; "));
  Serial.print(skipped);
  Serial.print(F(" auto-updated registers not compared"));
  nl();
}

// ---------------------------------------------------------------------------
// Stage 3: timing checks
// ---------------------------------------------------------------------------
static void timingTest() {
  const uint8_t N = 5;
  const uint32_t expPclkKhz = EXP_PCLK_KHZ;
  const uint32_t expPeriodUs = EXP_PERIOD_US;
  const uint32_t expVsyncUs = EXP_VSYNC_US;

  MODE_CAPTURE();
  uint32_t sumP = 0, sumH = 0, minP = 0xffffffffUL, maxP = 0;
  bool ok = waitVsyncRise();
  uint32_t tRise = micros();
  for (uint8_t i = 0; ok && i < N; i++) {
    ok = waitVsyncLevel(false);
    uint32_t tFall = micros();
    ok = ok && waitVsyncLevel(true);
    uint32_t t = micros();
    if (!ok) {
      break;
    }
    uint32_t p = t - tRise;
    sumH += tFall - tRise;
    sumP += p;
    if (p < minP) minP = p;
    if (p > maxP) maxP = p;
    tRise = t;
  }
  MODE_SEND();
  if (!ok) {
    stln(S_FAIL, F("no VSYNC"));
    return;
  }

  uint32_t avgP = sumP / N;
  uint32_t avgH = sumH / N;
  st(S_INFO);
  Serial.print(F("frame period avg "));
  Serial.print(avgP);
  Serial.print(F(" us (min "));
  Serial.print(minP);
  Serial.print(F(", max "));
  Serial.print(maxP);
  Serial.print(F(", "));
  Serial.print(N);
  Serial.print(F(" frames), expected "));
  Serial.print(expPeriodUs);
  Serial.print(F(" us"));
  nl();

  st(S_INFO);
  Serial.print(F("VSYNC high avg "));
  Serial.print(avgH);
  Serial.print(F(" us, expected "));
  Serial.print(expVsyncUs);
  Serial.print(F(" us"));
  nl();

  uint32_t estKhz = expPclkKhz * expPeriodUs / avgP;
  st(S_INFO);
  Serial.print(F("estimated PCLK = "));
  Serial.print(estKhz);
  Serial.print(F(" kHz ("));
  Serial.print(expPclkKhz);
  Serial.print(F(" kHz x "));
  Serial.print(expPeriodUs);
  Serial.print(F(" us / measured frame period)"));
  nl();

  if (estKhz > MAX_PCLK_KHZ) {
    st(S_FAIL);
    Serial.print(F("PCLK above "));
    Serial.print(MAX_PCLK_KHZ);
    Serial.print(F(" kHz: too fast for the breadboard, increase CLKRC_VAL"));
    nl();
  } else if (estKhz < expPclkKhz * 7 / 10) {
    stln(S_WARN, F("PCLK far below expectation (check oscillator / CLKRC / DBLV)"));
  } else {
    stln(S_PASS, F("PCLK within limits"));
  }
}

// ---------------------------------------------------------------------------
// Stage 4: image capture, streaming and validation
// ---------------------------------------------------------------------------

// Counts colour-bar edges on one YUYV line. An edge is a pixel pair whose
// Y0, U, Y1 or V differs by more than BAR_JUMP from the previous pair.
// Edges in directly neighbouring pairs count once (a bar border falling
// inside a pair shows up in two successive pairs).
static uint8_t countBarEdges(const uint8_t *line) {
  uint8_t edges = 0;
  bool lastWasEdge = false;
  for (uint16_t i = 4; i < IMG_LINE_BYTES; i += 4) {
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

// Streams IMG_BYTES from address 0 as an image block and validates it on the
// fly. bars = colour-bar checks are PASS/FAIL (otherwise INFO).
// Returns the number of bar edges found on line REF_LINE.
static uint8_t sendImage(bool bars) {
  Serial.print(F("# IMAGE "));
  Serial.print(IMG_W);
  Serial.write(' ');
  Serial.print(IMG_H);
  Serial.print(F(" YUYV "));
  Serial.print(IMG_BYTES);
  nl();
  MODE_SEND();
  counterClear();

  uint8_t orMask = 0, andMask = 0xff, first = 0;
  bool allEqual = true;
  uint32_t mismatches = 0;   // bytes after REF_LINE that differ from REF_LINE
  uint16_t badLines = 0;     // lines after REF_LINE that differ from REF_LINE
  int16_t firstBadLine = -1;
  uint16_t lineChanges = 0;  // lines after REF_LINE that differ from the previous line
  uint8_t earlyChanged = 0;  // bit n set: line n+1 differs from line n (n < REF_LINE)
  uint16_t col = 0, line = 0;
  bool lineBad = false;
  // Line-to-line comparison uses a Fletcher-style checksum per line instead
  // of a second line buffer (a QVGA line is 640 bytes; RAM is 2 KB).
  uint16_t sum1 = 0, sum2 = 0, prevSum1 = 0, prevSum2 = 0;
  uint8_t buf[16];

  for (uint32_t k = 0; k < IMG_BYTES;) {
    for (uint8_t i = 0; i < 16; i++, k++) {
      if (k != 0) {
        aclkPulse();
      }
      uint8_t b = srReadByte();
      buf[i] = b;
      orMask |= b;
      andMask &= b;
      if (k == 0) {
        first = b;
      } else if (b != first) {
        allEqual = false;
      }
      sum1 += b;
      sum2 += sum1;
      // Compare with the reference line.
      if (line == REF_LINE) {
        refLine[col] = b;
      } else if (line > REF_LINE && b != refLine[col]) {
        mismatches++;
        if (!lineBad) {
          lineBad = true;
          badLines++;
          if (firstBadLine < 0) {
            firstBadLine = line;
          }
        }
      }
      if (++col == IMG_LINE_BYTES) {
        bool lineChanged = line != 0 && (sum1 != prevSum1 || sum2 != prevSum2);
        prevSum1 = sum1;
        prevSum2 = sum2;
        sum1 = sum2 = 0;
        if (lineChanged) {
          if (line <= REF_LINE) {
            earlyChanged |= (uint8_t)(1u << (line - 1));
          } else {
            lineChanges++;
          }
        }
        col = 0;
        line++;
        lineBad = false;
      }
    }
    printHexLine(buf, 16, false);
  }
  g_addr = IMG_BYTES - 1;
  Serial.print(F("# END IMAGE\n"));

  uint8_t edges = countBarEdges(refLine);

  // ---- Summary ----
  st(S_INFO);
  Serial.print(F("line "));
  Serial.print(REF_LINE);
  Serial.print(F(" starts (Y0 U0 Y1 V0 ...): "));
  for (uint8_t i = 0; i < 8; i++) {
    printHex2(refLine[i]);
    Serial.write(' ');
  }
  nl();

  if (allEqual) {
    st(S_FAIL);
    Serial.print(F("all "));
    Serial.print(IMG_BYTES);
    Serial.print(F(" bytes are "));
    printHexByte(first);
    Serial.print(F(": bus or SRAM dead"));
    nl();
  }

  uint8_t neverOne = ~orMask, neverZero = andMask;
  if (neverOne || neverZero) {
    st(bars ? S_FAIL : S_WARN);
    Serial.print(F("stuck bits in image: never 1 "));
    printHexByte(neverOne);
    Serial.print(F(", never 0 "));
    printHexByte(neverZero);
    nl();
  } else {
    stln(S_PASS, F("all data bits toggle in image"));
  }

  // First lines (may be garbage): INFO only.
  st(S_INFO);
  for (uint8_t n = 0; n < REF_LINE; n++) {
    if (n != 0) {
      Serial.print(F(", "));
    }
    Serial.print(F("line "));
    Serial.print(n);
    Serial.print(F("->"));
    Serial.print(n + 1);
    Serial.print((earlyChanged & (1u << n)) ? F(" differ") : F(" same"));
  }
  Serial.print(F(" (first lines may be garbage, not checked)"));
  nl();

  const uint16_t checkedLines = IMG_H - REF_LINE - 1;
  st(bars ? (mismatches ? S_FAIL : S_PASS) : S_INFO);
  if (mismatches == 0) {
    Serial.print(F("all "));
    Serial.print(checkedLines);
    Serial.print(F(" lines from "));
    Serial.print(REF_LINE + 1);
    Serial.print(F(" on identical to line "));
    Serial.print(REF_LINE);
  } else {
    Serial.print(mismatches);
    Serial.print(F(" bytes in "));
    Serial.print(badLines);
    Serial.print(F(" of "));
    Serial.print(checkedLines);
    Serial.print(F(" lines differ from line "));
    Serial.print(REF_LINE);
    Serial.print(F(", first bad line "));
    Serial.print(firstBadLine);
  }
  nl();

  if (mismatches != 0) {
    st(S_INFO);
    Serial.print(lineChanges);
    Serial.print(F(" line-to-line changes from line "));
    Serial.print(REF_LINE + 1);
    Serial.print(F(" on (a persistent shift gives 1, an isolated bad line 2)"));
    nl();
    if (bars) {
      if (badLines >= (uint16_t)(IMG_H - firstBadLine) * 3 / 4 &&
          lineChanges < badLines / 2) {
        stln(S_INFO, F("lines differ from the reference but rarely from each "
                       "other: counter probably skipped/double-counted "
                       "(PCLK ringing?)"));
      } else if (lineChanges >= badLines) {
        stln(S_INFO, F("differences come and go line to line: noisy bytes "
                       "(data bus / SRAM timing?)"));
      }
    }
  }

  st(bars ? ((edges >= BARS_MIN_EDGES && edges <= BARS_MAX_EDGES) ? S_PASS : S_FAIL)
          : S_INFO);
  Serial.print(F("line "));
  Serial.print(REF_LINE);
  Serial.print(F(" has "));
  Serial.print(edges);
  Serial.print(F(" bar edges (Y/U/V step > "));
  Serial.print(BAR_JUMP);
  Serial.print(F(" between pixel pairs; expect ~7 for 8 bars)"));
  nl();

  return edges;
}

static void printBarMode(uint8_t mode) {
  if (mode == BAR_MODE_COM17) {
    Serial.print(F("COM17 bit3=1 (DSP colour bar)"));
    return;
  }
  Serial.print(F("XSC bit7="));
  Serial.print(mode & 1);
  Serial.print(F(", YSC bit7="));
  Serial.print((mode >> 1) & 1);
}

static bool barsOk(uint8_t edges) {
  return edges >= BARS_MIN_EDGES && edges <= BARS_MAX_EDGES;
}

// Configure a colour-bar mode (auto exposure/gain/white balance off),
// capture and send. Returns bar edges, or NO_IMAGE if nothing was captured.
static uint8_t barsImage(uint8_t mode) {
  bool dsp = (mode == BAR_MODE_COM17);
  uint8_t s = ov7670_set_auto(false);
  if (s == 0) {
    s = ov7670_set_test_pattern(dsp ? OV7670_PATTERN_NONE : mode);
  }
  if (s == 0) {
    s = ov7670_set_dsp_colour_bar(dsp);
  }
  if (s != 0) {
    st(S_FAIL);
    Serial.print(F("setting colour-bar mode: "));
    printWireError(s);
    nl();
  }
  st(S_INFO);
  Serial.print(F("colour bars: "));
  printBarMode(mode);
  uint8_t xsc = 0, ysc = 0, com17 = 0;
  if (s == 0 &&
      ov7670_read(REG_SCALING_XSC, &xsc) == 0 &&
      ov7670_read(REG_SCALING_YSC, &ysc) == 0 &&
      ov7670_read(REG_COM17, &com17) == 0) {
    Serial.print(F(" (XSC="));
    printHexByte(xsc);
    Serial.print(F(", YSC="));
    printHexByte(ysc);
    Serial.print(F(", COM17="));
    printHexByte(com17);
    Serial.print(F("), AEC/AGC/AWB off"));
  } else {
    Serial.print(F(" (registers not readable)"));
  }
  nl();

  if (!snapshot(3)) {
    return NO_IMAGE;
  }
  return sendImage(true);
}

// Colour-bar modes tried in this order when no bars are found.
static const uint8_t BAR_MODES[] = {
  OV7670_PATTERN_BARS,    // kernel / datasheet bit labels: XSC.7=0, YSC.7=1
  OV7670_PATTERN_SHIFT1,  // swapped bit order: XSC.7=1, YSC.7=0
  BAR_MODE_COM17,         // DSP colour bar
};

static void colourBars(bool tryOthers) {
  uint8_t edges = barsImage(g_barMode);
  if (edges == NO_IMAGE) {
    return;
  }
  if (barsOk(edges)) {
    st(S_INFO);
    Serial.print(F("colour bars work with "));
    printBarMode(g_barMode);
    nl();
    return;
  }
  if (!tryOthers) {
    return;
  }
  uint8_t tried = g_barMode;
  for (uint8_t i = 0; i < sizeof(BAR_MODES); i++) {
    uint8_t mode = BAR_MODES[i];
    if (mode == tried) {
      continue;
    }
    st(S_WARN);
    Serial.print(F("no colour bars, retrying with "));
    printBarMode(mode);
    nl();
    edges = barsImage(mode);
    if (edges == NO_IMAGE) {
      return;
    }
    if (barsOk(edges)) {
      g_barMode = mode;
      st(S_INFO);
      Serial.print(F("colour bars work with "));
      printBarMode(mode);
      nl();
      return;
    }
  }
  stln(S_WARN, F("no colour bars with any test pattern setting"));
}

static void realImage() {
  uint8_t s = ov7670_set_test_pattern(OV7670_PATTERN_NONE);
  if (s == 0) {
    s = ov7670_set_dsp_colour_bar(false);
  }
  if (s == 0) {
    s = ov7670_set_auto(true);
  }
  if (s != 0) {
    st(S_FAIL);
    Serial.print(F("disabling test pattern / enabling AEC/AGC/AWB: "));
    printWireError(s);
    nl();
  }
  stln(S_INFO, F("test pattern off, AEC/AGC/AWB on, waiting 1 s to settle"));
  delay(1000);   // the camera runs (and adjusts exposure) in either MODE
  if (snapshot(2)) {
    sendImage(false);
  }
}

// ---------------------------------------------------------------------------
// Startup and command loop
// ---------------------------------------------------------------------------
static void waitForKey() {
  uint32_t last = 0;
  bool firstPrompt = true;
  while (!Serial.available()) {
    if (firstPrompt || millis() - last >= 5000) {
      Serial.print(F("# Press any key to start the tests\n"));
      last = millis();
      firstPrompt = false;
    }
  }
  delay(20);
  while (Serial.available()) {
    Serial.read();   // discard the key (and CR/LF)
  }
}

static void printHelp() {
  stln(S_INFO, F("commands: b = colour bars, r = real image, m = memory test, "
                 "t = timing test, d = read-path diagnostics, p = probe clear path, h = help"));
}

void setup() {
  // ---- Stage 0 ----
  initPins();
  Serial.begin(BAUD);
  Serial.print(F("\n# arducam OV7670 test firmware ("));
  Serial.print(F(__DATE__));
  Serial.print(F(")\n"));
  stageHeader(0, F("init done (MODE=SEND, camera powered down)"));
  waitForKey();

  stageHeader(1, F("memory + counter read test (camera powered down)"));
  memoryTest();

  stageHeader(2, F("camera bring-up and SCCB tests"));
  cameraBringUp();

  stageHeader(3, F("timing checks"));
  timingTest();

  stageHeader(4, F("colour-bar test image"));
  colourBars(true);

  st(S_INFO);
  Serial.print(F("all stages done: "));
  Serial.print(g_count[S_PASS]);
  Serial.print(F(" PASS, "));
  Serial.print(g_count[S_WARN]);
  Serial.print(F(" WARN, "));
  Serial.print(g_count[S_FAIL]);
  Serial.print(F(" FAIL"));
  nl();
  printHelp();
}

void loop() {
  if (!Serial.available()) {
    return;
  }
  char c = Serial.read();
  switch (c) {
    case 'b':
      stageHeader(4, F("colour-bar test image"));
      colourBars(false);
      break;
    case 'r':
      stageHeader(4, F("real image"));
      realImage();
      break;
    case 'm':
      stageHeader(1, F("memory + counter read test"));
      memoryTest();
      break;
    case 't':
      stageHeader(3, F("timing checks"));
      timingTest();
      break;
    case 'd':
      Serial.print(F("# STAGE D: read-path diagnostics\n"));
      readPathDiag();
      break;
    case 'p':
      Serial.print(F("# STAGE P: probe counter clear path\n"));
      probeClear();
      break;
    case 'h':
    case '?':
      printHelp();
      return;
    case '\r':
    case '\n':
    case ' ':
      return;
    default:
      st(S_WARN);
      Serial.print(F("unknown command '"));
      Serial.write(c);
      Serial.write('\'');
      nl();
      printHelp();
      return;
  }
  Serial.print(F("# READY\n"));
}
