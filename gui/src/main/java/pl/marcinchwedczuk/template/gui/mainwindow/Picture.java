package pl.marcinchwedczuk.template.gui.mainwindow;

import pl.marcinchwedczuk.template.domain.image.RgbImage;
import pl.marcinchwedczuk.template.domain.protocol.RawImage;

/** Picture as received from the camera, and decoded to RGB (sensor orientation). */
public record Picture(RawImage raw, RgbImage rgb) { }
