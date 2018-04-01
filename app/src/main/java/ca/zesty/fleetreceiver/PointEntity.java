package ca.zesty.fleetreceiver;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Entity(tableName = "points")
public class PointEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @ColumnInfo(name = "reporter_id") public String reporterId;
    @ColumnInfo(name = "time") public long time;
    @ColumnInfo(name = "latitude") public double latitude;
    @ColumnInfo(name = "longitude") double longitude;
    @ColumnInfo(name = "altitude") public double altitude;
    @ColumnInfo(name = "speed") public double speed;
    @ColumnInfo(name = "bearing") public double bearing;
    @ColumnInfo(name = "lat_lon_sd") public double latLonSd;
    @ColumnInfo(name = "type") public char type;
    @ColumnInfo(name = "last_transition") public long lastTransition;

    static final Pattern PATTERN_TYPE = Pattern.compile("(\\d+)([rmgs])");

    public PointEntity() { }

    /** Formats a point for readability and debugging. */
    public String toString() {
        String fix =  String.format(
            Locale.US, "%s: (%+.5f, %+.5f, %+.0f m), %.1f m/s brg %.0f, sd=%.0f m",
            Utils.formatUtcTimeSeconds(time * 1000),
            latitude, longitude, altitude, speed, bearing, latLonSd
        );

        return String.format(Locale.US, "<%s, %s %d s%s>", fix,
            (type == 'r' || type == 'g') ? "rested" : "moved",
            time - lastTransition,
            type == 'g' ? ", go" : type == 's' ? ", stop" : ""
        );
    }

    public static PointEntity parse(String reporterId, String text) {
        PointEntity point = new PointEntity();
        point.reporterId = reporterId;

        String[] parts = text.split(";");
        if (parts.length != 8) return null;

        Long time = Utils.parseTimestamp(parts[0]);
        if (time == null) return null;
        point.time = time / 1000;

        Matcher matcher = PATTERN_TYPE.matcher(parts[7]);
        if (!matcher.matches()) return null;
        point.type = matcher.group(2).charAt(0);
        try {
            point.latitude = Double.parseDouble(parts[1]);
            point.longitude = Double.parseDouble(parts[2]);
            point.altitude = Double.parseDouble(parts[3]);
            point.speed = Double.parseDouble(parts[4]);
            point.bearing = Double.parseDouble(parts[5]);
            point.latLonSd = Double.parseDouble(parts[6]);
            point.lastTransition = point.time - Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        return point;
    }
}
