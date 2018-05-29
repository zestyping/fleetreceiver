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
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;


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
    private AppDatabase mDb = AppDatabase.getDatabase(this);
    private MapView mMapView;
    private Map<String, Marker> mMarkers = new HashMap<>();
    private Map<String, String> mLabels = new HashMap<>();
    private Map<String, LatLong> mPositions = new HashMap<>();
    private Map<String, PointEntity> mPoints = new HashMap<>();
    private String mSelectedReporterId = null;
    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private Marker mSelectionMarker = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        startService(new Intent(getApplicationContext(), ReceiverService.class));
        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_FLEET_RECEIVER_LOG_MESSAGE));
        registerReceiver(mPointsAddedReceiver, new IntentFilter(ReceiverService.ACTION_FLEET_RECEIVER_POINTS_ADDED));

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
        if (item.getItemId() == R.id.action_add_map_data) {
            u.showMessageBox("Add map data",
                "To add data to the map, please download any MapsForge file " +
                "with a filename ending in \".map\" and leave it in your " +
                "Download folder.\n\n" +
                "This map will load and show the information from all the " +
                "\".map\" files in your Download folder.");
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
        return false;
    }

    void initializeMap() {
        TileCache tileCache = AndroidUtil.createTileCache(this, "mapcache",
            mMapView.getModel().displayModel.getTileSize(), 1f,
            mMapView.getModel().frameBufferModel.getOverdrawFactor());
        final MultiMapDataStore multiMap = new MultiMapDataStore(
            MultiMapDataStore.DataPolicy.RETURN_ALL);
        addMapFile(multiMap, getAssetFile("world.map"));
        addMapFilesInDir(multiMap, Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS));
        TileRendererLayer tileRendererLayer = new TileRendererLayer(tileCache, multiMap,
            mMapView.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
        tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);

        mMapView.addLayer(tileRendererLayer);
        mMapView.addLayer(new ReporterLayer());
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

    void addMapFilesInDir(MultiMapDataStore multiMap, File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    addMapFilesInDir(multiMap, file);
                }
                if (file.isFile() && file.getName().toLowerCase().endsWith(".map")) {

                    addMapFile(multiMap, file);
                }
            }
        }
    }

    void addMapFile(MultiMapDataStore multiMap, File file) {
        try {
            MapDataStore map = new MapFile(file);
            multiMap.addMapDataStore(map, true, true);
            Log.i(TAG, "Loaded map from " + file.getAbsolutePath());
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
        for (ReporterEntity.WithPoint rp : mDb.getReporterDao().getAllActiveWithLatestPoints()) {
            if (rp.point != null) positions.add(new LatLong(rp.point.latitude, rp.point.longitude));
        }
        if (positions.size() > 0) {
            BoundingBox bounds = new BoundingBox(positions);
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
        long now = System.currentTimeMillis();
        mPositions.clear();
        mPoints.clear();
        mLabels.clear();
        for (ReporterEntity.WithPoint rp : mDb.getReporterDao().getAllActiveWithLatestPoints()) {
            if (rp.point == null) continue;

            LatLong position = new LatLong(rp.point.latitude, rp.point.longitude);
            mPositions.put(rp.reporter.reporterId, position);
            mPoints.put(rp.reporter.reporterId, rp.point);
            mLabels.put(rp.reporter.reporterId, rp.reporter.label);
        }
        if (mSelectedReporterId != null && !mPositions.containsKey(mSelectedReporterId)) {
            // The selected reporter was deactivated or deleted.
            mSelectedReporterId = null;
            updateReporterFrame();
        }
        mMapView.getLayerManager().redrawLayers();
    }

    void updateReporterFrame() {
        if (mSelectedReporterId == null) {
            u.showFrameChild(R.id.reporter_summary);
            u.setText(R.id.registered_count, "" + mDb.getReporterDao().getAllActive().size());
            long oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000;
            u.setText(R.id.reported_count, "" + mDb.getReporterDao().getAllReportedSince(oneHourAgo).size());
        } else {
            u.showFrameChild(R.id.reporter_details);
            ReporterEntity r = mDb.getReporterDao().get(mSelectedReporterId);
            PointEntity p = mDb.getPointDao().getLatestPointForReporter(mSelectedReporterId);
            u.setText(R.id.speed, Utils.format("%.0f km/h", p.speedKmh));
            u.setText(R.id.speed_details, Utils.format("as of " + Utils.describeTime(p.timeMillis)));
            long lastTransitionMillis = p.isTransition() ? p.timeMillis : p.lastTransitionMillis;
            u.setText(R.id.motion, Utils.describePeriod(System.currentTimeMillis() - lastTransitionMillis));
            u.setText(R.id.motion_details, p.isResting() ? "stopped at this spot" : "in motion");
        }
    }

    /** Zooms the map while holding the selected marker in a fixed position. */
    void zoom(int change) {
        MapViewPosition pos = mMapView.getModel().mapViewPosition;
        byte zoom = pos.getZoomLevel();
        byte newZoom = (byte) (zoom + change);
        if (newZoom < pos.getZoomLevelMin()) newZoom = pos.getZoomLevelMin();
        if (newZoom > pos.getZoomLevelMax()) newZoom = pos.getZoomLevelMax();
        change = newZoom - zoom;

        LatLong pivot = mPositions.get(mSelectedReporterId);
        if (pivot == null) {
            pos.setZoomLevel(newZoom, true);
            return;
        }
        MapViewProjection proj = mMapView.getMapViewProjection();
        Point centerPoint = proj.toPixels(pos.getCenter());
        Point pivotPoint = proj.toPixels(pivot);
        double dx = centerPoint.x - pivotPoint.x;
        double dy = centerPoint.y - pivotPoint.y;
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

    Point toPixels(LatLong position, long mapSize, Point topLeftPoint) {
        return new Point(
            MercatorProjection.longitudeToPixelX(position.longitude, mapSize) - topLeftPoint.x,
            MercatorProjection.latitudeToPixelY(position.latitude, mapSize) - topLeftPoint.y
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
        for (PointEntity point : mPoints.values()) {
            maxSpeedKmh = Math.max(maxSpeedKmh, point.speedKmh);
        }
        double maxSeconds = (dimension / 3) / (maxSpeedKmh * 1000 / 3600);
        for (int seconds : new int[] {3600, 1800, 900, 600, 300, 120, 60, 30, 15, 10, 5, 2, 1}) {
            if (seconds < maxSeconds) return seconds;
        }
        return 1;
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

        Map<String, Point> drawnPoints = new HashMap<>();

        void drawStalenessArc(Canvas canvas, int cx, int cy, long timeMillis) {
            long minSinceReport = (System.currentTimeMillis() - timeMillis) / MINUTE;
            Paint arcPaint = minSinceReport > u.getIntPref(Prefs.EXPECTED_REPORTING_INTERVAL, 10) ?
                getStrokePaint(0xffff0000, 2) : getStrokePaint(0xfff0a000, 2);
            getAndroidCanvas(canvas).drawArc(
                new RectF(cx - DOT_RADIUS, cy - DOT_RADIUS, cx + DOT_RADIUS, cy + DOT_RADIUS),
                270, Math.min(360, minSinceReport * 6), false, getAndroidPaint(arcPaint)
            );
        }

        @Override public void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPoint) {
            long mapSize = MercatorProjection.getMapSize(zoomLevel, this.displayModel.getTileSize());
            Rectangle canvasRect = new Rectangle(0, 0, canvas.getWidth(), canvas.getHeight());
            Rectangle canvasEnvelope = canvasRect.envelope(DOT_RADIUS + SHADOW);

            Paint backgroundPaint = getFillPaint(0x60ffffff);
            Paint dotPaint = getFillPaint(0xff20a040);
            setShadowLayer(dotPaint, SHADOW, 0, 0, 0xc0000000);
            Paint arrowPaint = getStrokePaint(0xff20a040, 3);
            Paint trackPaint = getStrokePaint(0xc020a040, 3);
            setDashPattern(trackPaint, 2, 6);
            Paint arrowOutlinePaint = getStrokePaint(0xc0ffffff, 3);
            Paint selectedArrowOutlinePaint = getStrokePaint(0xffffffff, 2);
            Paint arrowTextPaint = getTextPaint(0xff20a040, 12, FontStyle.BOLD, Align.CENTER);
            Paint circlePaint = getStrokePaint(0xffffffff, 2);
            Paint textPaint = getTextPaint(0xff000000, 12, FontStyle.BOLD, Align.CENTER);
            Paint softOutlinePaint = getTextOutlinePaint(textPaint, 0xc0ffffff, 4);
            Paint hardOutlinePaint = getTextOutlinePaint(textPaint, 0xffffffff, 2);
            Paint selectedOutlinePaint = getTextOutlinePaint(textPaint, 0xffffffff, 4);

            Point selectedCenter = null;
            drawnPoints.clear();
            long now = System.currentTimeMillis();

            drawRect(canvas, canvasEnvelope, backgroundPaint);
            int arrowSeconds = getArrowSeconds(boundingBox);

            for (String reporterId : mPositions.keySet()) {
                LatLong position = mPositions.get(reporterId);
                String label = mLabels.get(reporterId);
                Point center = toPixels(position, mapSize, topLeftPoint);
                if (reporterId.equals(mSelectedReporterId)) {
                    selectedCenter = center;
                    continue;
                }
                if (canvasEnvelope.contains(center)) {
                    int cx = (int) center.x;
                    int cy = (int) center.y;
                    LatLong reckonPos = deadReckon(mPoints.get(reporterId), arrowSeconds);
                    Point arrowHead = toPixels(reckonPos, mapSize, topLeftPoint);

                    drawArrowOutline(canvas, center, arrowHead, ARROW_TIP_SIZE, arrowOutlinePaint, 2);
                    drawArrow(canvas, center, arrowHead, ARROW_TIP_SIZE, arrowPaint);
                    canvas.drawText(label, cx, cy + LABEL_OFFSET, softOutlinePaint);
                    canvas.drawCircle(cx, cy, DOT_RADIUS, dotPaint);
                    canvas.drawCircle(cx, cy, DOT_RADIUS, circlePaint);
                    drawStalenessArc(canvas, cx, cy, mPoints.get(reporterId).timeMillis);

                    drawnPoints.put(reporterId, center);
                }
            }

            // Draw all text on top of all dots.
            for (String reporterId : drawnPoints.keySet()) {
                String label = mLabels.get(reporterId);
                Point point = drawnPoints.get(reporterId);
                int cx = (int) point.x;
                int cy = (int) point.y;
                canvas.drawText(label, cx, cy + LABEL_OFFSET, hardOutlinePaint);
                canvas.drawText(label, cx, cy + LABEL_OFFSET, textPaint);
            }

            // Draw selected reporter last (i.e. on top).
            if (selectedCenter != null) {
                long timeMillis = mPoints.get(mSelectedReporterId).timeMillis;
                String label = mLabels.get(mSelectedReporterId) + " (" + Utils.describeTime(timeMillis) + ")";
                int cx = (int) selectedCenter.x;
                int cy = (int) selectedCenter.y;
                LatLong reckonPos = deadReckon(mPoints.get(mSelectedReporterId), arrowSeconds);
                Point arrowHead = toPixels(reckonPos, mapSize, topLeftPoint);

                Path track = AndroidGraphicFactory.INSTANCE.createPath();
                boolean firstPoint = true;
                for (PointEntity point : mDb.getPointDao().getAllForReporterSince(mSelectedReporterId, now - HOUR)) {
                    Point p = toPixels(new LatLong(point.latitude, point.longitude), mapSize, topLeftPoint);
                    if (firstPoint) track.moveTo((float) p.x, (float) p.y);
                    else track.lineTo((float) p.x, (float) p.y);
                    firstPoint = false;
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

                drawArrowOutline(canvas, selectedCenter, arrowHead, ARROW_TIP_SIZE, selectedArrowOutlinePaint, 2);
                if (drawArrow(canvas, selectedCenter, arrowHead, ARROW_TIP_SIZE, arrowPaint)) {
                    String arrowLabel = Utils.describePeriod(arrowSeconds * 1000, true);
                    // Place above or below the arrowhead.
                    int ax = (int) arrowHead.x;
                    int ay = (int) arrowHead.y + (arrowHead.y > selectedCenter.y ? 1 : -1) * (TEXT_HEIGHT/2 + PADDING) + TEXT_HEIGHT/2;
                    if (Math.abs(arrowHead.x - selectedCenter.x) > Math.abs(arrowHead.y - selectedCenter.y)) {
                        // Place left or right of the arrowhead.
                        int sx = arrowHead.x > selectedCenter.x ? 1 : -1;
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
        }

        public boolean onTap(LatLong tap, Point layerPoint, Point tapPoint) {
            tapPoint = mMapView.getMapViewProjection().toPixels(tap);
            String lastSelected = mSelectedReporterId;
            mSelectedReporterId = null;
            for (String reporterId : drawnPoints.keySet()) {
                LatLong position = mPositions.get(reporterId);
                Point point = mMapView.getMapViewProjection().toPixels(position);
                if (Math.abs(point.x - tapPoint.x) < TAP_RADIUS &&
                    Math.abs(point.y - tapPoint.y) < TAP_RADIUS &&
                    !reporterId.equals(lastSelected)) {
                    mSelectedReporterId = reporterId;
                    break;
                }
            }
            updateReporterFrame();
            mMapView.getLayerManager().redrawLayers();
            return true;
        }
    }
}
