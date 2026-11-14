# arducam serial protocol — DRAFT v1

Protocol between the `arduino/arducam` firmware (Arduino Uno) and the Java
PC application. Status: **v1, implemented in firmware 0.1.0, untested on
hardware**.

## 1. Link

- 250000 baud, 8N1, no flow control.
- The serial goes through an external USB-serial module, and the board has
  no bootloader. **Opening the port does not reset the Arduino**, so the
  host cannot rely on seeing the boot message (use `STATUS`).
- Throughput is about 25,000 bytes/s (10 bits per byte). A QVGA image
  (153,600 bytes) takes about 6.1 s; QQVGA (38,400 bytes) about 1.5 s.

## 2. General rules

- **Dumb firmware, smart host.** The firmware only executes commands. It
  never acts on its own: no automatic power-up, no idle power-down, no
  retries. All decisions and image processing (demosaicing, rotation,
  validation, retrying) belong to the Java application.
- **Strict request/response.** The host sends one command, then reads until
  that command's final line (`OK` or `ERR`) before sending the next one.
  Commands sent while the firmware is busy are not guaranteed to be read.
- **Nothing unsolicited**, except a single `BOOT` line after reset.
- **On boot the firmware does nothing**: camera powered down (PWDN high),
  MODE = SEND, no image in memory.
- Text is ASCII, and every text line ends with `\n` (`\r` is never sent).
  Binary data appears only inside a `DATA` block (section 5).
- **Forward compatibility:** the host must ignore unknown line types and
  unknown `key=value` pairs.

## 3. Commands (host → firmware)

```
VERB [arg ...]\n
```

- ASCII, case-insensitive, separated by single spaces, at most 63
  characters. `\r` is ignored, so `\r\n` also works.
- Arguments are `key=value` (no spaces). Numbers are decimal, or hex with a
  `0x` prefix.
- Empty lines are ignored. The host should send a lone `\n` first after
  opening the port, to flush any partial line.

| Verb | Arguments | Needs power | Purpose |
|---|---|---|---|
| `STATUS` | — | no | firmware version, state |
| `POWER` | `on` \| `off` \| (none) | — | power up / down / query |
| `RESET` | — | yes | hardware reset of the camera |
| `MEMTEST` | — | no | counter + SRAM read-path test |
| `CAMTEST` | `image=0\|1` | yes | SCCB, registers, timing, colour bars |
| `PICTURE` | see 6.6 | yes | capture and send an image |
| `READ` | `offset=` `len=` | no | re-send bytes of the last image from SRAM |

## 4. Response lines (firmware → host)

Each line starts with a type token:

| Line | Meaning |
|---|---|
| `BOOT key=value ...` | sent once after reset |
| `OK <VERB> [key=value ...]` | **final**: success |
| `ERR <VERB> <CODE> [free text]` | **final**: failure |
| `INFO <free text>` | progress or log; the host may display or ignore it |
| `TEST <name> <PASS\|WARN\|FAIL> [key=value ...] [free text]` | one sub-test result (MEMTEST / CAMTEST) |
| `DATA key=value ...` | start of a binary block (section 5) |
| `END crc16=<hhhh>` | end of a binary block |

`ERR` for an unknown verb uses `?` as the verb: `ERR ? UNKNOWN_CMD FOO`.

## 5. Binary blocks

```
DATA bytes=<n> offset=<o> w=<w> h=<h> fmt=<YUYV|BAYER>\n
<exactly n raw bytes>
END crc16=<4 lowercase hex digits>\n
```

`offset` is the position of the first byte within the image (0 for a whole
image); `w`, `h`, `fmt` describe the whole image.

- After the `DATA` line the host switches to binary mode, reads **exactly
  n bytes**, then returns to line mode and reads the `END` line.
- The CRC is computed while streaming, which is why it comes in the
  trailer. It covers the n payload bytes and uses **CRC-16/XMODEM**: poly
  0x1021, init 0x0000, no reflection, no final XOR. That is avr-libc
  `_crc_xmodem_update()`. Check value: `"123456789"` → `0x31c3`.
- If the CRC does not match, the host can fetch the image again with
  `READ` without taking a new picture (section 6.7).

## 6. Command details

### 6.1 Boot

```
BOOT fw=arducam ver=0.1.0 proto=1
```

### 6.2 STATUS

```
> STATUS
< OK STATUS fw=arducam ver=0.1.0 proto=1 power=off cam=unknown image=none
```

| Key | Values |
|---|---|
| `fw` | `arducam` |
| `ver` | firmware version, semver |
| `proto` | protocol version (this document: `1`) |
| `power` | `on` \| `off` |
| `cam` | `unknown` (never checked since power-up) \| `ok` (SCCB ID matched) \| `fail` |
| `image` | `none` \| `<w>x<h>/<fmt>`, e.g. `320x240/YUYV`, `640x480/BAYER`: what is in SRAM |

Use it as the handshake after opening the port.

### 6.3 POWER

```
> POWER on
< OK POWER state=on pid=0x76 ver=0x73
> POWER
< OK POWER state=on
> POWER off
< OK POWER state=off
```

- `on`: PWDN low, wait 300 ms, RESET low 10 ms, then high, wait 100 ms,
  then check the SCCB ID (PID 0x76, VER 0x73).
  - If SCCB does not answer: `ERR POWER SCCB ...`. The camera stays
    powered, `cam=fail`, so the host can retry `RESET` or turn it off.
- `off`: PWDN high. The image in SRAM stays valid (the camera cannot
  write while MODE = SEND).
- No argument: report the state only.
- `on` when already on, or `off` when already off, is not an error.

### 6.4 RESET

```
> RESET
< OK RESET pid=0x76 ver=0x73
```

Hardware reset pulse, then the SCCB ID check. Registers return to their
defaults; `PICTURE` always writes the full configuration, so nothing else
is needed. Errors: `POWER_OFF`, `SCCB`.

### 6.5 MEMTEST

Read-only test of the counter and the SRAM → 74HC166 path (the Arduino
cannot write the SRAM; the write path is covered by `CAMTEST`). Works with
the camera off.

```
> MEMTEST
< TEST data_path PASS
< TEST counter_clear PASS
< TEST step_short PASS
< TEST step_long PASS steps=1023
< OK MEMTEST result=pass
```

`result` is `pass` if there is no FAIL (WARN allowed), otherwise `fail`.
`MEMTEST` itself fails with `ERR` only for protocol errors; a failing test
still ends with `OK MEMTEST result=fail`.

### 6.6 CAMTEST

```
> CAMTEST
< TEST sccb_id PASS pid=0x76 ver=0x73 midh=0x7f midl=0xa2
< TEST sccb_rw PASS
< TEST registers PASS checked=126 mismatched=0 critical=0 write_errors=0
< TEST timing PASS frame_us=398732 vsync_us=2344 pclk_khz=501
< TEST bars PASS edges=7 bad_lines=0 never1=0x00 never0=0x00
< OK CAMTEST result=pass
```

- `registers`: FAIL if a write failed or a critical register (clock, format,
  window) differs, WARN for other differences. Each differing register is
  also reported as an `INFO reg 0x.. wrote 0x.. read 0x.. mask 0x..` line.
- `timing`: FAIL if PCLK > 1100 kHz, WARN if below 70 % of the expected
  500 kHz.
- `bars`: captures a QQVGA colour-bar image (COM17 DSP colour bar) and
  checks it: stuck bits (`never1`/`never0` masks), lines 3..119 identical to
  line 2 (`bad_lines`), 5..9 bar edges.
- `image=1` also sends that image as a `DATA` block (format as in
  `PICTURE`) before `OK`.
- Afterwards the bar image is in SRAM (`image=160x120/YUYV`).
- Errors: `POWER_OFF`, `SCCB`, `NO_VSYNC`.

### 6.7 PICTURE

```
> PICTURE res=qvga
< INFO configuring 320x240 YUYV
< INFO settling 1000 ms
< DATA bytes=153600 offset=0 w=320 h=240 fmt=YUYV
<   ...153600 raw bytes...
< END crc16=1a2b
< OK PICTURE w=320 h=240 fmt=YUYV bytes=153600
```

| Argument | Values | Default |
|---|---|---|
| `fmt` | `yuyv` \| `bayer` | `yuyv` |
| `res` | `qqvga` (160×120) \| `qvga` (320×240) \| `vga` (640×480) | `qvga` |
| `pattern` | `none` \| `bars` | `none` |
| `auto` | `on` \| `off`: AEC/AGC/AWB | `on` |
| `settle` | 0–10000 ms to wait after configuring | `1000` |
| `send` | `1` = send the image, `0` = capture only (fetch with `READ`) | `1` |

Defaults are fixed; the firmware does not derive one argument from
another. For colour bars the host should send e.g.
`PICTURE pattern=bars auto=off settle=0`.

Supported combinations (anything else → `ERR PICTURE BAD_ARG`):

| `fmt` | `res` | Bytes | Bytes/row | Transfer |
|---|---|---|---|---|
| `yuyv` | `qqvga` | 38,400 | 320 | ~1.5 s |
| `yuyv` | `qvga` | 153,600 | 640 | ~6.1 s |
| `bayer` | `vga` | 307,200 | 640 | ~12.3 s |

(VGA YUYV, 614,400 bytes, does not fit in the 512 KB SRAM.)

- **`YUYV`:** 2 bytes/pixel, `Y0 U0 Y1 V0`, rows top to bottom, `w*2`
  bytes per row, no padding.
- **`BAYER`:** raw sensor data, 1 byte/pixel, rows top to bottom, `w` bytes
  per row. Pattern **BGGR**: even rows `B G B G …`, odd rows `G R G R …`
  (the kernel declares `SBGGR8`; to be confirmed on the hardware). No
  gamma, no colour processing. The host must demosaic.
  `pattern=bars` may not produce bars in raw mode (the colour bars are
  generated in the DSP); untested.
- **Orientation:** the image is sent as the sensor delivers it. The camera
  is mounted rotated, so the host should rotate **90° clockwise** for
  display.
- **The first 2–4 pixels of each row may be junk** until the window
  offset is fixed.
- Errors: `POWER_OFF`, `SCCB`, `NO_VSYNC`, `BAD_ARG`.

### 6.8 READ

```
> READ
< DATA bytes=153600 offset=0 w=320 h=240 fmt=YUYV
< ...
< END crc16=1a2b
< OK READ
> READ offset=0 len=4096
< DATA bytes=4096 offset=0 w=320 h=240 fmt=YUYV
< ...
```

Re-reads the last captured image from SRAM. Defaults: `offset=0`, `len` =
the rest of the image. Errors: `NO_IMAGE`, and `BAD_ARG` if the range is
outside the image.

## 7. Error codes

| Code | Meaning |
|---|---|
| `UNKNOWN_CMD` | unknown verb |
| `BAD_ARG` | unknown key, bad value, or out of range |
| `TOO_LONG` | command line longer than 63 characters (the rest of the line is discarded) |
| `POWER_OFF` | the command needs the camera powered on |
| `SCCB` | the camera does not answer on SCCB (I²C) |
| `NO_VSYNC` | no VSYNC within the timeout |
| `NO_IMAGE` | `READ` with no image in SRAM |

## 8. Host timeouts (suggested)

Measured from sending the command to the final line, with PCLK 500 kHz.

| Command | Typical | Suggested timeout |
|---|---|---|
| `STATUS`, `POWER off` | < 10 ms | 1 s |
| `POWER on`, `RESET` | ~0.5 s | 3 s |
| `MEMTEST` | < 0.2 s | 3 s |
| `CAMTEST` | ~6 s (+1.5 s with `image=1`) | 20 s |
| `PICTURE res=qqvga` | ~1 s settle + ~2 s capture + 1.5 s data | 15 s |
| `PICTURE res=qvga` | ~1 s settle + ~4 s capture + 6.1 s data | 25 s |
| `PICTURE fmt=bayer res=vga` | ~1 s settle + ~4 s capture + 12.3 s data | 35 s |
| `READ` (whole image) | same as the data part above | data time + 10 s |

While waiting, `INFO` lines may arrive at any time. During a `DATA` block
the host should time out if no byte arrives for 1 s.

## 9. Firmware implementation notes

- **Resolution and format at runtime:** the configure function in
  `arduino/common` must take the mode as a parameter, replacing the
  compile-time `OV7670_QVGA`. CLKRC is chosen per mode so that PCLK stays
  at 500 kHz with the 8 MHz oscillator. Internal clock =
  XCLK / (2 × (CLKRC + 1)), as measured.

  | Mode | Scaling | PCLK | CLKRC | Frame period |
  |---|---|---|---|---|
  | QQVGA YUYV | DCW ÷4, PCLK ÷4 | internal / 4 | 0x01 | 400 ms |
  | QVGA YUYV | DCW ÷2, PCLK ÷2 | internal / 2 | 0x03 | 800 ms |
  | VGA Bayer | none (COM3 = COM14 = 0) | internal | 0x07 | ~800 ms * |

  \* Bayer outputs 1 byte per pixel at the full pixel rate. The kernel
  notes the frame rate doubles in raw mode at the same CLKRC. **To be
  measured.**

  VGA Bayer register set: kernel `ov7670_fmt_raw` (COM7 = 0x01 Bayer VGA,
  COM13 = 0x08, COM16 = 0x3d, REG76 = 0xe1), no DCW (COM3 = 0x00,
  COM14 = 0x00, 0x72 = 0x11, 0x73 = 0xf0), plus the usual overrides
  (DBLV, COM10, CLKRC). COM13's UV-swap bit does not apply to raw data.
- **No image validation in `PICTURE`**: just capture and stream. The
  validation logic lives in `CAMTEST` (QQVGA bars only) and in the host.
- Command line buffer: 64 bytes. Image streaming reads the SRAM through the
  counter and 74HC166 directly into `Serial.write()`; no image buffering in
  RAM.
- The image metadata (`w`, `h`, `fmt`, `bytes`) is kept in RAM.
  `image=none` after boot.

## 10. Decisions

1. `PICTURE` with the camera off returns `ERR PICTURE POWER_OFF`. The host
   powers the camera on explicitly.
2. No automatic idle power-down in v1.
3. No abort command in v1. The host can use `READ` with `offset`/`len` for
   partial transfers.
4. VGA is supported as raw Bayer (`fmt=bayer res=vga`).
