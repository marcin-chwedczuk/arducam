# Arducam

JavaFX application that connects to an Arduino via a serial port and displays
images captured by a simple camera module.

Currently it is a skeleton: it lets you pick a serial port, connect to it
and shows every line received from the Arduino in the log window.
Use `arduino/serial-test/serial-test.ino` to test the connection.

How to build and run the app:
```
# Build the app
./mvnw clean install

# Run the app
./mvnw javafx:run -pl gui

# Run in the debug mode (waits for the debugger)
./mvnw javafx:run -pl gui -Pdebug
```

Serial port settings: 9600 baud, 8 data bits, 1 stop bit, no parity.
Make sure that no other application (e.g. Arduino IDE Serial Monitor) is using the port.

This app is using `jSerialComm` library for serial communication with Arduino.
