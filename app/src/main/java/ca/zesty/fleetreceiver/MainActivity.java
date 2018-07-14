package ca.zesty.fleetreceiver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.DashPathEffect;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.util.Pair;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.crashlytics.android.Crashlytics;

import org.mapsforge.core.graphics.Align;
import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.FontFamily;
import org.mapsforge.core.graphics.FontStyle;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Path;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.model.Rectangle;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidPreferences;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.model.MapViewPosition;
import org.mapsforge.map.model.common.PreferencesFacade;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import org.mapsforge.map.util.MapViewProjection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.fabric.sdk.android.Fabric;

public class MainActivity extends BaseActivity {
    static final String TAG = "MainActivity";
    static final String ACTION_FLEET_RECEIVER_LOG_MESSAGE = "FLEET_RECEIVER_LOG_MESSAGE";
    static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";
    static final long DISPLAY_INTERVAL_MILLIS = 5*1000;
    static final int MAX_ZOOM_IN_LEVEL = 14;
    static final double TAU = 2 * Math.PI;
    static final double DEGREE = TAU / 360;
    static final double EARTH_RADIUS = 6371009;  // meters
    static final long SECOND = 1000;
    static final long MINUTE = 60 * SECOND;
    static final long HOUR = 60 * MINUTE;

    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();
    private PointsAddedReceiver mPointsAddedReceiver = new PointsAddedReceiver();
    private MapView mMapView;
    private Map<String, ReporterEntity.WithPoint> mReporterPoints = new HashMap<>();
    private String mSelectedReporterId = null;
    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private Marker mSelectionMarker = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Fabric.with(this, new Crashlytics());
        setContentView(R.layout.activity_main);
        setTitle("Fleet Receiver " + BuildConfig.VERSION_NAME);

        ActivityCompat.requestPermissions(this, new String[] {
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.WAKE_LOCK
        }, 0);

        AndroidGraphicFactory.createInstance(getApplication());
        mMapView = (MapView) findViewById(R.id.map);
        initializeMap();
        startService(new Intent(getApplicationContext(), NotificationService.class));
        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_FLEET_RECEIVER_LOG_MESSAGE));
        registerReceiver(mPointsAddedReceiver, new IntentFilter(SmsPointReceiver.ACTION_FLEET_RECEIVER_POINTS_ADDED));

        findViewById(R.id.zoom_points).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                zoomToAllPoints();
            }
        });

        // Some elements of the display show elapsed time, so we need to
        // periodically update the display even if there are no new events.
        mHandler = new Handler();
        mRunnable = new Runnable() {
            public void run() {
                updateMarkers();
                updateReporterFrame();
                mHandler.postDelayed(mRunnable, DISPLAY_INTERVAL_MILLIS);
            }
        };
    }

    @Override protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRunnable, 0);
        u.show(R.id.message_log, u.getBooleanPref(Prefs.SHOW_LOG, false));
    }

    @Override protected void onPause() {
        mHandler.removeCallbacks(mRunnable);
        super.onPause();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(mLogMessageReceiver);
        unregisterReceiver(mPointsAddedReceiver);
        saveMapViewPosition();
        mMapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(
        int requestCode, String permissions[], int[] grantResults) {
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, 0);
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_registration) {
            startActivity(new Intent(this, RegistrationActivity.class));
        }
        if (item.getItemId() == R.id.action_load_map_data) {
            List<File> loadedFiles = new ArrayList<>();
            reloadMapData(loadedFiles);
            String message = "Loaded map data from:\n";
            for (File file : loadedFiles) {
                message += "    \u2022 " + file.getName() + "\n";
            }
            message += "\nTo add more map data, download any MapsForge file with a " +
                "filename ending in \".map\" and leave it in your Download folder.";
            u.showMessageBox("Load map data", message);
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
        return false;
    }

    MapDataStore reloadMapData(List<File> loadedFiles) {
        TileCache tileCache = AndroidUtil.createTileCache(this, "mapcache",
            mMapView.getModel().displayModel.getTileSize(), 1f,
            mMapView.getModel().frameBufferModel.getOverdrawFactor());
        MultiMapDataStore multiMap = new MultiMapDataStore(
            MultiMapDataStore.DataPolicy.RETURN_ALL);
        addMapFile(multiMap, getAssetFile("world.map"), loadedFiles);
        addMapFilesInDir(multiMap, Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), loadedFiles);
        TileRendererLayer tileRendererLayer = new TileRendererLayer(tileCache, multiMap,
            mMapView.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
        tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);

        mMapView.getLayerManager().getLayers().clear();
        mMapView.addLayer(tileRendererLayer);
        mMapView.addLayer(new ReporterLayer());
        return multiMap;
    }

    void initializeMap() {
        final MapDataStore multiMap = reloadMapData(new ArrayList<File>());
        mMapView.setClickable(true);
        mMapView.getMapScaleBar().setVisible(true);
        mMapView.setBuiltInZoomControls(true);
        mMapView.getMapZoomControls().setAutoHide(false);
        mMapView.getMapZoomControls().getChildAt(0).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                zoom(-1);
            }
        });
        mMapView.getMapZoomControls().getChildAt(1).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                zoom(1);
            }
        });
        mMapView.post(new Runnable() {
            @Override public void run() {
                if (!restoreMapViewPosition()) {
                    if (!zoomToAllPoints()) {
                        mMapView.setCenter(multiMap.startPosition());
                        mMapView.setZoomLevel(multiMap.startZoomLevel());
                    }
                }
            }
        });
    }

    static void addMapFilesInDir(MultiMapDataStore multiMap, File dir, List<File> loadedFiles) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    addMapFilesInDir(multiMap, file, loadedFiles);
                }
                if (file.isFile() && file.getName().toLowerCase().endsWith(".map")) {
                    addMapFile(multiMap, file, loadedFiles);
                }
            }
        }
    }

    static void addMapFile(MultiMapDataStore multiMap, File file, List<File> loadedFiles) {
        try {
            MapDataStore map = new MapFile(file);
            multiMap.addMapDataStore(map, true, true);
            Log.i(TAG, "Loaded map from " + file.getAbsolutePath());
            loadedFiles.add(file);
        } catch (Exception e) {
            Log.e(TAG, "Could not load map from " + file.getAbsolutePath(), e);
            e.printStackTrace();
        }
    }

    File getAssetFile(String name) {
        File destination = new File(getCacheDir(), name);
        if (!destination.exists()) {
            try {
                InputStream in = getAssets().open(name);
                OutputStream out = new FileOutputStream(destination);
                byte[] buffer = new byte[8192];
                int count = in.read(buffer);
                while (count >= 0) {
                    out.write(buffer, 0, count);
                    count = in.read(buffer);
                }
                out.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not copy asset to " + destination, e);
            }
        }
        return destination;
    }

    void saveMapViewPosition() {
        PreferencesFacade facade = new AndroidPreferences(u.getPrefs());
        mMapView.getModel().mapViewPosition.save(facade);
        facade.save();
    }

    boolean restoreMapViewPosition() {
        MapViewPosition mvp = mMapView.getModel().mapViewPosition;
        mvp.init(new AndroidPreferences(u.getPrefs()));
        return !(mvp.getCenter().latitude == 0 &&
                 mvp.getCenter().longitude == 0 &&
                 mvp.getZoomLevel() == 0);
    }

    boolean zoomToAllPoints() {
        List<LatLong> positions = new ArrayList<>();
        AppDatabase db = AppDatabase.getDatabase(this);
        try {
            for (ReporterEntity.WithPoint rp : db.getReporterDao().getAllActiveWithLatestPoints()) {
                if (rp.point != null) positions.add(new LatLong(rp.point.latitude, rp.point.longitude));
            }
        } finally {
            db.close();
        }
        if (positions.size() > 0) {
            BoundingBox bounds = new BoundingBox(positions).extendMargin(1.2f);
            mMapView.setCenter(bounds.getCenterPoint());
            int zoomLevel = mMapView.getModel().mapViewPosition.getZoomLevel();
            int tileSize = mMapView.getModel().displayModel.getTileSize();
            // The idea of zoomLimit is to avoid zooming in past MAX_ZOOM_IN_LEVEL,
            // but if already zoomed in beyond that, avoid needlessly zooming out.
            int zoomLimit = Math.max(zoomLevel, MAX_ZOOM_IN_LEVEL);
            mMapView.setZoomLevel(positions.size() == 1 ? (byte) zoomLimit :
                (byte) Math.min(zoomLimit, LatLongUtils.zoomForBounds(
                    mMapView.getDimension(), bounds, tileSize
                ))
            );
            return true;
        }
        return false;
    }

    class LogMessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(EXTRA_LOG_MESSAGE)) {
                String message = intent.getStringExtra(EXTRA_LOG_MESSAGE);
                ((TextView) findViewById(R.id.message_log)).append(message + "\n");
            }
        }
    }

    public static void postLogMessage(Context context, String message) {
        Intent intent = new Intent(ACTION_FLEET_RECEIVER_LOG_MESSAGE);
        intent.putExtra(EXTRA_LOG_MESSAGE,
            Utils.formatUtcTimeSeconds(System.currentTimeMillis()) + " - " + message);
        context.sendBroadcast(intent);
    }

    class PointsAddedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateMarkers();
            updateReporterFrame();
        }
    }

    void updateMarkers() {
        Map<String, ReporterEntity.WithPoint> reporterPoints = new HashMap<>();
        AppDatabase db = AppDatabase.getDatabase(this);
        try {
            for (ReporterEntity.WithPoint rp : db.getReporterDao().getAllActiveWithLatestPoints()) {
                if (rp.point != null) reporterPoints.put(rp.reporter.reporterId, rp);
            }
        } finally {
            db.close();
        }
        mReporterPoints = reporterPoints;  // this is iterated over; update it atomically
        if (mSelectedReporterId != null && !mReporterPoints.containsKey(mSelectedReporterId)) {
            // The selected reporter was deactivated or deleted.
            mSelectedReporterId = null;
            updateReporterFrame();
        }
        mMapView.getLayerManager().redrawLayers();
    }

    void updateReporterFrame() {
        AppDatabase db = AppDatabase.getDatabase(this);
        try {
            if (mSelectedReporterId == null) {
                u.showFrameChild(R.id.reporter_summary);
                u.setText(R.id.registered_count, "" + db.getReporterDao().getAllActive().size());

                long oneHourAgo = System.currentTimeMillis() - HOUR;
                u.setText(R.id.reported_count, "" + db.getReporterDao().getAllReportedSince(oneHourAgo).size());
            } else {
                u.showFrameChild(R.id.reporter_details);
                ReporterEntity r = db.getReporterDao().get(mSelectedReporterId);
                PointEntity p = db.getPointDao().getLatestPointForReporter(mSelectedReporterId);
                u.setText(R.id.label, r.label);
                long minSinceReport = (System.currentTimeMillis() - p.timeMillis)/MINUTE;
                long expectedIntervalMin = u.getIntPref(Prefs.EXPECTED_REPORTING_INTERVAL, 10);
                u.setText(R.id.label_details,
                    Utils.format("last report " + Utils.describeTime(p
                        .timeMillis)),
                    minSinceReport > expectedIntervalMin ? 0xffe04020 : 0x8a000000);
                long lastTransitionMillis = p.isTransition() ? p.timeMillis : p.lastTransitionMillis;
                u.setText(R.id.speed, Utils.format("%.0f km/h", p.speedKmh));
                u.setText(R.id.speed_details,
                    (p.isResting() ? "stopped" : "started") + " moving " +
                        Utils.describeTime(lastTransitionMillis)
                );
            }
        } finally {
            db.close();
        }
    }

    LatLong getReporterPosition(String reporterId) {
        ReporterEntity.WithPoint rp = mReporterPoints.get(reporterId);
        return rp != null ? new LatLong(rp.point.latitude, rp.point.longitude) : null;
    }

    /** Zooms the map while holding the selected marker in a fixed position. */
    void zoom(int change) {
        MapViewPosition pos = mMapView.getModel().mapViewPosition;
        byte zoom = pos.getZoomLevel();
        byte newZoom = (byte) (zoom + change);
        if (newZoom < pos.getZoomLevelMin()) newZoom = pos.getZoomLevelMin();
        if (newZoom > pos.getZoomLevelMax()) newZoom = pos.getZoomLevelMax();
        change = newZoom - zoom;

        LatLong pivot = getReporterPosition(mSelectedReporterId);
        if (pivot == null) {
            pos.setZoomLevel(newZoom, true);
            return;
        }
        MapViewProjection proj = mMapView.getMapViewProjection();
        Point centerPt = proj.toPixels(pos.getCenter());
        Point pivotPt = proj.toPixels(pivot);
        double dx = centerPt.x - pivotPt.x;
        double dy = centerPt.y - pivotPt.y;
        double factor = 1 - Math.pow(0.5, change);
        pos.moveCenterAndZoom(dx * factor, dy * factor, (byte) change, false);
    }

    int dpToPixels(double dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    android.graphics.Canvas getAndroidCanvas(Canvas canvas) {
        try {
            Field field = canvas.getClass().getDeclaredField("canvas");
            field.setAccessible(true);
            return (android.graphics.Canvas) field.get(canvas);
        } catch (NoSuchFieldException e) {
        } catch (IllegalAccessException e) {
        }
        return null;
    }

    android.graphics.Paint getAndroidPaint(Paint paint) {
        try {
            Field field = paint.getClass().getDeclaredField("paint");
            field.setAccessible(true);
            return (android.graphics.Paint) field.get(paint);
        } catch (NoSuchFieldException e) {
        } catch (IllegalAccessException e) {
        }
        return null;
    }

    void setStrokeCap(Paint paint, Cap cap) {
        android.graphics.Paint aPaint = getAndroidPaint(paint);
        if (aPaint != null) aPaint.setStrokeCap(cap);
    }

    void setStrokeJoin(Paint paint, Join join) {
        android.graphics.Paint aPaint = getAndroidPaint(paint);
        if (aPaint != null) aPaint.setStrokeJoin(join);
    }

    void setShadowLayer(Paint paint, double radius, double dx, double dy, int color) {
        android.graphics.Paint aPaint = getAndroidPaint(paint);
        if (aPaint != null) aPaint.setShadowLayer((float) radius, (float) dx, (float) dy, color);
    }

    void setDashPattern(Paint paint, double on, double off) {
        android.graphics.Paint aPaint = getAndroidPaint(paint);
        if (aPaint != null) {
            float density = getResources().getDisplayMetrics().density;
            float[] lengths = new float[] {(float) on * density, (float) off * density};
            aPaint.setPathEffect(new DashPathEffect(lengths, 0));
        }
    }

    double measureText(Paint paint, String text) {
        return getAndroidPaint(paint).measureText(text);
    }

    Paint clonePaint(Paint paint) {
        return AndroidGraphicFactory.INSTANCE.createPaint(paint);
    }

    Paint getStrokePaint(int color, double strokeWidthDp, Cap cap) {
        Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
        paint.setStyle(Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(dpToPixels(strokeWidthDp));
        setStrokeCap(paint, cap);
        setStrokeJoin(paint, Join.ROUND);
        return paint;
    }

    Paint getStrokePaint(int color, double strokeWidthDp) {
        return getStrokePaint(color, strokeWidthDp, Cap.BUTT);
    }

    Paint getFillPaint(int color) {
        Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
        paint.setStyle(Style.FILL);
        paint.setColor(color);
        return paint;
    }

    Paint getTextPaint(int color, double sizeDp, FontStyle fontStyle, Align align) {
        Paint paint = getFillPaint(color);
        paint.setTextSize(dpToPixels(sizeDp));
        paint.setTypeface(FontFamily.DEFAULT, fontStyle);
        paint.setTextAlign(align);
        return paint;
    }

    Paint getTextOutlinePaint(Paint textPaint, int color, double strokeWidthDp) {
        Paint paint = clonePaint(textPaint);
        paint.setStyle(Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(dpToPixels(strokeWidthDp));
        return paint;
    }

    void drawRect(Canvas canvas, Rectangle rect, Paint paint) {
        Path path = AndroidGraphicFactory.INSTANCE.createPath();
        path.moveTo((float) rect.left, (float) rect.top);
        path.lineTo((float) rect.right, (float) rect.top);
        path.lineTo((float) rect.right, (float) rect.bottom);
        path.lineTo((float) rect.left, (float) rect.bottom);
        path.lineTo((float) rect.left, (float) rect.top);
        canvas.drawPath(path, paint);
    }

    boolean drawArrow(Canvas canvas, Point tail, Point head, double size, Paint tipPaint, Paint bodyPaint) {
        double dx = head.x - tail.x, dy = head.y - tail.y;
        double d = Math.sqrt(dx*dx + dy*dy);
        if (d < size) return false;
        double ux = dx/d, uy = dy/d;
        double px = uy, py = -ux;

        Path body = AndroidGraphicFactory.INSTANCE.createPath();
        body.moveTo((float) tail.x, (float) tail.y);
        body.lineTo((float) (head.x - ux*size), (float) (head.y - uy*size));
        Path tip = AndroidGraphicFactory.INSTANCE.createPath();
        tip.moveTo((float) head.x, (float) head.y);
        tip.lineTo((float) (head.x - ux*size - px*size/2), (float) (head.y - uy*size - py*size/2));
        tip.lineTo((float) (head.x - ux*size + px*size/2), (float) (head.y - uy*size + py*size/2));
        tip.lineTo((float) head.x, (float) head.y);

        canvas.drawPath(body, bodyPaint);
        canvas.drawPath(tip, tipPaint);
        return true;
    }

    /** Draws an arrow.  The paint's stroke width should be the thickness of the arrow body. */
    boolean drawArrow(Canvas canvas, Point tail, Point head, double size, Paint paint) {
        Paint bodyPaint = clonePaint(paint);
        Paint tipPaint = clonePaint(paint);
        bodyPaint.setStyle(Style.STROKE);
        tipPaint.setStyle(Style.FILL);
        tipPaint.setStrokeWidth(0);
        return drawArrow(canvas, tail, head, size, tipPaint, bodyPaint);
    }

    /** Draws the outline of an arrow.  The paint's stroke width should be the thickness of the arrow body. */
    boolean drawArrowOutline(Canvas canvas, Point tail, Point head, double size, Paint paint, double outlineWidthDp) {
        Paint bodyPaint = clonePaint(paint);
        Paint tipPaint = clonePaint(paint);
        bodyPaint.setStyle(Style.STROKE);
        bodyPaint.setStrokeWidth(bodyPaint.getStrokeWidth() + dpToPixels(outlineWidthDp));
        tipPaint.setStyle(Style.STROKE);
        tipPaint.setStrokeWidth(dpToPixels(outlineWidthDp));
        return drawArrow(canvas, tail, head, size, tipPaint, bodyPaint);
    }

    Point toPixels(LatLong position, long mapSize, Point topLeftPt) {
        return new Point(
            MercatorProjection.longitudeToPixelX(position.longitude, mapSize) - topLeftPt.x,
            MercatorProjection.latitudeToPixelY(position.latitude, mapSize) - topLeftPt.y
        );
    }

    /** Estimates a new position by dead reckoning from the current position
        using the current bearing and speed.  This calculation assumes a
        locally flat earth, which is reasonably accurate for short distances.
     */
    LatLong deadReckon(PointEntity point, double seconds) {
        double meters = point.speedKmh * 1000 * seconds / 3600;
        double latRadPerMeter = 1 / EARTH_RADIUS;
        double lonRadPerMeter = 1 / Math.cos(point.latitude *DEGREE) / EARTH_RADIUS;
        double northRadians = Math.cos(point.bearing *DEGREE) * meters * latRadPerMeter;
        double eastRadians = Math.sin(point.bearing *DEGREE) * meters * lonRadPerMeter;
        double latitude = point.latitude + northRadians /DEGREE;
        double longitude = point.longitude + eastRadians /DEGREE;
        return new LatLong(
            latitude < -90 ? -90 : latitude > 90 ? 90 : latitude,
            longitude < -180 ? latitude + 360 : longitude >= 180 ? longitude - 360 : longitude
        );
    }

    /** Decides on the time period that determines the length of velocity arrows. */
    int getArrowSeconds(BoundingBox box) {
        double heightMeters = EARTH_RADIUS * box.getLatitudeSpan() *DEGREE;
        double topWidthMeters = EARTH_RADIUS * Math.cos(box.maxLatitude *DEGREE) * box.getLongitudeSpan() *DEGREE;
        double bottomWidthMeters = EARTH_RADIUS * Math.cos(box.minLatitude *DEGREE) * box.getLongitudeSpan() *DEGREE;
        double dimension = Math.min(Math.min(topWidthMeters, bottomWidthMeters), heightMeters);
        double maxSpeedKmh = 30;
        for (ReporterEntity.WithPoint rp : mReporterPoints.values()) {
            maxSpeedKmh = Math.max(maxSpeedKmh, rp.point.speedKmh);
        }
        double maxSeconds = (dimension / 3) / (maxSpeedKmh * 1000 / 3600);
        for (int seconds : new int[] {3600, 1800, 900, 600, 300, 120, 60}) {
            if (seconds < maxSeconds) return seconds;
        }
        return 60;
    }

    class ReporterLayer extends Layer {
        final int DOT_RADIUS = dpToPixels(6);
        final int TAP_RADIUS = dpToPixels(18);
        final int SHADOW = dpToPixels(6);
        final int FRAME_RADIUS = dpToPixels(12);
        final int ARROW_TIP_SIZE = dpToPixels(9);
        final int TEXT_HEIGHT = dpToPixels(12) * 3/4;
        final int PADDING = dpToPixels(4);
        final int LABEL_OFFSET = FRAME_RADIUS + PADDING + TEXT_HEIGHT;

        List<Pair<String, LatLong>> mDrawnPositions = new ArrayList<>();

        @Override public void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPt) {
            long mapSize = MercatorProjection.getMapSize(zoomLevel, this.displayModel.getTileSize());
            Rectangle canvasRect = new Rectangle(0, 0, canvas.getWidth(), canvas.getHeight());
            Rectangle canvasEnvelope = canvasRect.envelope(DOT_RADIUS + SHADOW);

            Paint backgroundPaint = getFillPaint(0x60ffffff);
            Paint dotPaint = getFillPaint(0xff20a040);
            setShadowLayer(dotPaint, SHADOW, 0, 0, 0xc0000000);
            Paint arrowPaint = getStrokePaint(0xff20a040, 3);
            Paint trackPaint = getStrokePaint(0xc020a040, 3, Cap.ROUND);
            setDashPattern(trackPaint, 2, 6);
            Paint arrowOutlinePaint = getStrokePaint(0xc0ffffff, 3);
            Paint selectedArrowOutlinePaint = getStrokePaint(0xffffffff, 2);
            Paint arrowTextPaint = getTextPaint(0xff20a040, 12, FontStyle.BOLD, Align.CENTER);
            Paint circlePaint = getStrokePaint(0xffffffff, 2);
            Paint textPaint = getTextPaint(0xff000000, 12, FontStyle.BOLD, Align.CENTER);
            Paint softOutlinePaint = getTextOutlinePaint(textPaint, 0xc0ffffff, 4);
            Paint hardOutlinePaint = getTextOutlinePaint(textPaint, 0xffffffff, 2);
            Paint selectedOutlinePaint = getTextOutlinePaint(textPaint, 0xffffffff, 4);

            Point selectedPt = null;
            long now = System.currentTimeMillis();

            drawRect(canvas, canvasEnvelope, backgroundPaint);
            int arrowSeconds = getArrowSeconds(boundingBox);

            final Map<String, ReporterEntity.WithPoint> reporterPoints = new HashMap<>(mReporterPoints);
            List<String> reporterIds = new ArrayList<>(reporterPoints.keySet());

            // Ensure out-of-contact reporters are visible by drawing the
            // oldest points last (on top).
            Collections.sort(reporterIds, new Comparator<String>() {
                public int compare(String a, String b) {
                    long timeA = reporterPoints.get(a).point.timeMillis;
                    long timeB = reporterPoints.get(b).point.timeMillis;
                    return timeA < timeB ? 1 : timeA > timeB ? -1 : 0;
                }
            });

            // Keep track of what we've drawn, for reuse in onTap() below.
            List<Pair<String, LatLong>> drawnPositions = new ArrayList<>();

            for (String reporterId : reporterIds) {
                LatLong position = getReporterPosition(reporterId);
                String label = reporterPoints.get(reporterId).reporter.label;
                PointEntity point = reporterPoints.get(reporterId).point;
                Point pt = toPixels(position, mapSize, topLeftPt);
                if (reporterId.equals(mSelectedReporterId)) {
                    selectedPt = pt;
                    continue;
                }
                if (canvasEnvelope.contains(pt)) {
                    LatLong reckonPos = deadReckon(point, arrowSeconds);
                    Point arrowHead = toPixels(reckonPos, mapSize, topLeftPt);

                    drawArrowOutline(canvas, pt, arrowHead, ARROW_TIP_SIZE, arrowOutlinePaint, 2);
                    drawArrow(canvas, pt, arrowHead, ARROW_TIP_SIZE, arrowPaint);
                    int x = (int) pt.x, y = (int) pt.y;
                    canvas.drawText(label, x, y + LABEL_OFFSET, softOutlinePaint);
                    canvas.drawCircle(x, y, DOT_RADIUS, dotPaint);
                    canvas.drawCircle(x, y, DOT_RADIUS, circlePaint);
                    drawStalenessArc(canvas, x, y, point.timeMillis);

                    drawnPositions.add(new Pair<>(reporterId, position));
                }
            }

            // Draw all text on top of all dots.
            for (Pair<String, LatLong> pair : drawnPositions) {
                String reporterId = pair.first;
                String label = reporterPoints.get(reporterId).reporter.label;
                Point pt = toPixels(pair.second, mapSize, topLeftPt);
                int x = (int) pt.x, y = (int) pt.y;
                canvas.drawText(label, x, y + LABEL_OFFSET, hardOutlinePaint);
                canvas.drawText(label, x, y + LABEL_OFFSET, textPaint);
            }

            // Draw selected reporter last (i.e. on top).
            if (selectedPt != null) {
                long timeMillis = reporterPoints.get(mSelectedReporterId).point.timeMillis;
                String label = reporterPoints.get(mSelectedReporterId).reporter.label +
                    " (" + Utils.describeTime(timeMillis) + ")";
                int cx = (int) selectedPt.x;
                int cy = (int) selectedPt.y;
                LatLong reckonPos = deadReckon(
                    reporterPoints.get(mSelectedReporterId).point, arrowSeconds);
                Point arrowHead = toPixels(reckonPos, mapSize, topLeftPt);

                boolean first = true;
                long startMillis = now - u.getIntPref(Prefs.HISTORICAL_TRACK_HOURS, 24) * HOUR;
                Path track = AndroidGraphicFactory.INSTANCE.createPath();
                AppDatabase db = AppDatabase.getDatabase(MainActivity.this);
                try {
                    for (PointEntity point : db.getPointDao().getAllForReporterSince(mSelectedReporterId, startMillis)) {
                        Point pt = toPixels(new LatLong(point.latitude, point.longitude), mapSize, topLeftPt);
                        if (first) track.moveTo((float) pt.x, (float) pt.y);
                        else track.lineTo((float) pt.x, (float) pt.y);
                        first = false;
                    }
                } finally {
                    db.close();
                }
                canvas.drawPath(track, trackPaint);

                Path path = AndroidGraphicFactory.INSTANCE.createPath();
                int scale = FRAME_RADIUS / 2;
                for (int xs = -1; xs <= 1; xs += 2) {
                    for (int ys = -1; ys <= 1; ys += 2) {
                        path.moveTo(cx + xs*scale*2, cy + ys*scale);
                        path.lineTo(cx + xs*scale*2, cy + ys*scale*2);
                        path.lineTo(cx + xs*scale, cy + ys*scale*2);
                    }
                }

                Paint frameShadowPaint = getStrokePaint(0xffffffff, 2);
                setShadowLayer(frameShadowPaint, SHADOW/2, 0, 0, 0xff000000);
                canvas.drawPath(path, frameShadowPaint);

                drawArrowOutline(canvas, selectedPt, arrowHead, ARROW_TIP_SIZE, selectedArrowOutlinePaint, 2);
                if (drawArrow(canvas, selectedPt, arrowHead, ARROW_TIP_SIZE, arrowPaint)) {
                    String arrowLabel = Utils.describePeriod(arrowSeconds * 1000, true);
                    // Place above or below the arrowhead.
                    int ax = (int) arrowHead.x;
                    int ay = (int) arrowHead.y + (arrowHead.y > selectedPt.y ? 1 : -1) * (TEXT_HEIGHT/2 + PADDING) + TEXT_HEIGHT/2;
                    if (Math.abs(arrowHead.x - selectedPt.x) > Math.abs(arrowHead.y - selectedPt.y)) {
                        // Place left or right of the arrowhead.
                        int sx = arrowHead.x > selectedPt.x ? 1 : -1;
                        ax = (int) (arrowHead.x + sx * (PADDING + measureText(arrowTextPaint, arrowLabel) / 2));
                        ay = (int) (arrowHead.y + TEXT_HEIGHT/2);
                    }

                    // Prevent collision with the marker label.
                    double textWidth = measureText(textPaint, label) + measureText(arrowTextPaint, arrowLabel);
                    if (ay > cy + FRAME_RADIUS && Math.abs(ax - cx) < textWidth/2 + PADDING) {
                        if (ay > cy + LABEL_OFFSET) {  // nudge down
                            ay = Math.max(ay, cy + LABEL_OFFSET + PADDING + TEXT_HEIGHT);
                        } else {  // nudge up
                            ay = Math.min(ay, cy + LABEL_OFFSET - PADDING - TEXT_HEIGHT);
                        }
                    }
                    canvas.drawText(arrowLabel, ax, ay, hardOutlinePaint);
                    canvas.drawText(arrowLabel, ax, ay, arrowTextPaint);
                }

                canvas.drawCircle(cx, cy, DOT_RADIUS, dotPaint);
                canvas.drawCircle(cx, cy, DOT_RADIUS + 1, getStrokePaint(0xc0000000, 2));
                canvas.drawCircle(cx, cy, DOT_RADIUS, circlePaint);
                drawStalenessArc(canvas, cx, cy, timeMillis);

                canvas.drawText(label, cx, cy + LABEL_OFFSET, selectedOutlinePaint);
                canvas.drawPath(path, getStrokePaint(0xc0000000, 4, Cap.ROUND));
                canvas.drawPath(path, getStrokePaint(0xffffffff, 2, Cap.ROUND));
                canvas.drawText(label, cx, cy + LABEL_OFFSET, textPaint);
            }

            mDrawnPositions = drawnPositions;
        }

        public boolean onTap(LatLong tap, Point layerPt, Point tapPt) {
            tapPt = mMapView.getMapViewProjection().toPixels(tap);
            String lastSelected = mSelectedReporterId;
            mSelectedReporterId = null;
            for (Pair<String, LatLong> pair : mDrawnPositions) {
                String reporterId = pair.first;
                LatLong position = pair.second;
                Point pt = mMapView.getMapViewProjection().toPixels(position);
                if (Math.abs(pt.x - tapPt.x) < TAP_RADIUS &&
                    Math.abs(pt.y - tapPt.y) < TAP_RADIUS &&
                    !reporterId.equals(lastSelected)) {
                    mSelectedReporterId = reporterId;
                    break;
                }
            }
            updateReporterFrame();
            mMapView.getLayerManager().redrawLayers();
            return true;
        }

        void drawStalenessArc(Canvas canvas, int cx, int cy, long timeMillis) {
            long minSinceReport = (System.currentTimeMillis() - timeMillis) / MINUTE;
            Paint arcPaint = minSinceReport > u.getIntPref(Prefs.EXPECTED_REPORTING_INTERVAL, 10) ?
                getStrokePaint(0xffff0000, 2) : getStrokePaint(0xfff0a000, 2);
            getAndroidCanvas(canvas).drawArc(
                new RectF(cx - DOT_RADIUS, cy - DOT_RADIUS, cx + DOT_RADIUS, cy + DOT_RADIUS),
                270, Math.min(360, minSinceReport * 6), false, getAndroidPaint(arcPaint)
            );
        }
    }
}
