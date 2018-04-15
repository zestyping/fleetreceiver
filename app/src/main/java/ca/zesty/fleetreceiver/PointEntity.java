package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Entity(tableName = "points")
public class PointEntity {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "point_id") public long pointId;
    @ColumnInfo(name = "reporter_id") public String reporterId;
    @ColumnInfo(name = "time_millis") public long timeMillis;
    @ColumnInfo(name = "latitude") public double latitude;
    @ColumnInfo(name = "longitude") double longitude;
    @ColumnInfo(name = "altitude") public double altitude;
    @ColumnInfo(name = "speed_kmh") public double speedKmh;
    @ColumnInfo(name = "bearing") public double bearing;
    @ColumnInfo(name = "lat_lon_sd") public double latLonSd;
    @ColumnInfo(name = "type") public char type;
    @ColumnInfo(name = "last_transition_millis") public long lastTransitionMillis;

    static final Pattern PATTERN_TYPE = Pattern.compile("(\\d+)([rmgs])");

    public PointEntity() { }

    /** Formats a point for readability and debugging. */
    public String toString() {
        String fix =  String.format(
            Locale.US, "%s: (%+.5f, %+.5f, %+.0f m), %.0f km/h brg %.0f, sd=%.0f m",
            Utils.formatUtcTimeSeconds(timeMillis),
            latitude, longitude, altitude, speedKmh, bearing, latLonSd
        );

        return String.format(Locale.US, "<%s, %s %d s%s>", fix,
            (type == 'r' || type == 'g') ? "rested" : "moved",
            (timeMillis - lastTransitionMillis) / 1000,
            type == 'g' ? ", go" : type == 's' ? ", stop" : ""
        );
    }

    public static PointEntity parse(String reporterId, String text) {
        PointEntity point = new PointEntity();
        point.reporterId = reporterId;

        String[] parts = text.split(";");
        if (parts.length != 8) return null;

        Long millis = Utils.parseTimestamp(parts[0]);
        if (millis == null) return null;
        point.timeMillis = millis;

        Matcher matcher = PATTERN_TYPE.matcher(parts[7]);
        if (!matcher.matches()) return null;
        int secondsSinceTransition = Integer.parseInt(matcher.group(1));
        point.type = matcher.group(2).charAt(0);
        try {
            point.latitude = Double.parseDouble(parts[1]);
            point.longitude = Double.parseDouble(parts[2]);
            point.altitude = Double.parseDouble(parts[3]);
            point.speedKmh = Double.parseDouble(parts[4]);
            point.bearing = Double.parseDouble(parts[5]);
            point.latLonSd = Double.parseDouble(parts[6]);
            point.lastTransitionMillis = point.timeMillis - secondsSinceTransition * 1000;
        } catch (NumberFormatException e) {
            return null;
        }
        return point;
    }
}
