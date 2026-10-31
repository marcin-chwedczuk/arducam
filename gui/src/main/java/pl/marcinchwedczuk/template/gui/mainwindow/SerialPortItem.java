package pl.marcinchwedczuk.template.gui.mainwindow;

import com.fazecast.jSerialComm.SerialPort;

public record SerialPortItem(SerialPort serialPort) {
    @Override
    public String toString() {
        return String.format("%s - %s", serialPort.getManufacturer(), serialPort.getSystemPortName());
    }
}
