package pl.marcinchwedczuk.template.domain.protocol;

/** Parsed {@code OK STATUS ...} line. Missing keys are reported as "?". */
public record DeviceStatus(String firmware, String version, String protocol,
                           String power, String camera, String image) {
    public static DeviceStatus from(ResponseLine ok) {
        return new DeviceStatus(
                get(ok, "fw"), get(ok, "ver"), get(ok, "proto"),
                get(ok, "power"), get(ok, "cam"), get(ok, "image"));
    }

    private static String get(ResponseLine line, String key) {
        String v = line.param(key);
        return v == null ? "?" : v;
    }

    @Override
    public String toString() {
        return String.format("%s %s · camera %s · image %s", firmware, version, camera, image);
    }
}
