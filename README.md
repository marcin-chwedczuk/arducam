# Arducam

JavaFX application that connects to an Arduino via a serial port and displays
images captured by a simple camera module.

The firmware is in `arduino/arducam`, the serial protocol is described in
[`arduino/PROTOCOL.md`](arduino/PROTOCOL.md). The application can:

- take pictures: QQVGA 160×120 YUYV, QVGA 320×240 YUYV, VGA 640×480 raw Bayer
  and a QQVGA colour bar test pattern,
- run the firmware memory test and camera test,
- rotate pictures (by default they are rotated 90° clockwise, because
  the camera is mounted rotated) and save them as PNG or BMP.

The camera is powered on only for the duration of camera commands.
All commands and responses are logged, except binary image data.

How to build and run the app:
```
# Build the app
./mvnw clean install

# Run the app
./mvnw javafx:run -pl gui

# Run in the debug mode (waits for the debugger)
./mvnw javafx:run -pl gui -Pdebug
```

Serial port settings: 250000 baud, 8 data bits, 1 stop bit, no parity.
The Arduino is connected through an external USB-serial (FTDI) module,
so opening the port does not reset it.
Make sure that no other application (e.g. Arduino IDE Serial Monitor) is using the port.

This app is using `jSerialComm` library for serial communication with Arduino.
