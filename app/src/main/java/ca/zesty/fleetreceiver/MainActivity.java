package ca.zesty.fleetreceiver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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
        for (ReporterEntity.WithPoint rp : mDb.getReporterDao().getAllActiveWithLatestPoints()) {
            if (rp.point == null) continue;

            LatLong position = new LatLong(rp.point.latitude, rp.point.longitude);
            mPositions.put(rp.reporter.reporterId, position);
            mPoints.put(rp.reporter.reporterId, rp.point);
            mLabels.put(rp.reporter.reporterId, rp.reporter.label);
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
            u.setText(R.id.motion, Utils.describePeriod(System.currentTimeMillis() - p.lastTransitionMillis));
            u.setText(R.id.motion_details, p.isResting() ? "stopped at this spot" : "since last stop");
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

    Paint clonePaint(Paint paint) {
        return AndroidGraphicFactory.INSTANCE.createPaint(paint);
    }

    Paint getStrokePaint(int color, double strokeWidthDp) {
        Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
        paint.setStyle(Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(dpToPixels(strokeWidthDp));
        setStrokeJoin(paint, Join.MITER);
        return paint;
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

    class ReporterLayer extends Layer {
        final int DOT_RADIUS = dpToPixels(6);
        final int TAP_RADIUS = dpToPixels(18);
        final int SHADOW = dpToPixels(6);
        final int LABEL_OFFSET = dpToPixels(24);
        final int FRAME_RADIUS = dpToPixels(12);

        Map<String, Point> drawnPoints = new HashMap<>();

        @Override public void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPoint) {
            long mapSize = MercatorProjection.getMapSize(zoomLevel, this.displayModel.getTileSize());
            Rectangle canvasRect = new Rectangle(0, 0, canvas.getWidth(), canvas.getHeight());
            Rectangle canvasEnvelope = canvasRect.envelope(DOT_RADIUS + SHADOW);

            Paint backgroundPaint = getFillPaint(0x60ffffff);
            Paint dotPaint = getFillPaint(0xff20a040);
            setShadowLayer(dotPaint, SHADOW, 0, 0, 0xc0000000);
            Paint circlePaint = getStrokePaint(0xffffffff, 2);
            Paint arcPaint = getStrokePaint(0xffff0000, 2);
            Paint textPaint = getTextPaint(0xff000000, 12, FontStyle.BOLD, Align.CENTER);
            Paint softOutlinePaint = getTextOutlinePaint(textPaint, 0xc0ffffff, 4);
            Paint hardOutlinePaint = getTextOutlinePaint(textPaint, 0xffffffff, 1);
            Paint selectedOutlinePaint = getTextOutlinePaint(textPaint, 0xffffffff, 4);

            Point selectedCenter = null;
            drawnPoints.clear();
            long now = System.currentTimeMillis();

            drawRect(canvas, canvasEnvelope, backgroundPaint);

            for (String reporterId : mPositions.keySet()) {
                LatLong position = mPositions.get(reporterId);
                String label = mLabels.get(reporterId);
                int cx = (int) (MercatorProjection.longitudeToPixelX(position.longitude, mapSize) - topLeftPoint.x);
                int cy = (int) (MercatorProjection.latitudeToPixelY(position.latitude, mapSize) - topLeftPoint.y);
                Point center = new Point(cx, cy);
                if (reporterId.equals(mSelectedReporterId)) {
                    selectedCenter = center;
                    continue;
                }
                if (canvasEnvelope.contains(center)) {
                    drawnPoints.put(reporterId, center);
                    canvas.drawText(label, cx, cy + LABEL_OFFSET, softOutlinePaint);
                    canvas.drawCircle(cx, cy, DOT_RADIUS, dotPaint);
                    canvas.drawCircle(cx, cy, DOT_RADIUS, circlePaint);
                    long minSinceReport = (now - mPoints.get(reporterId).timeMillis) / 60000;
                    getAndroidCanvas(canvas).drawArc(
                        new RectF(cx - DOT_RADIUS, cy - DOT_RADIUS, cx + DOT_RADIUS, cy + DOT_RADIUS),
                        270, Math.min(360, minSinceReport * 6), false, getAndroidPaint(arcPaint)
                    );
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
                String label = mLabels.get(mSelectedReporterId);
                long minSinceReport = (now - mPoints.get(mSelectedReporterId).timeMillis) / 60000;
                int cx = (int) selectedCenter.x;
                int cy = (int) selectedCenter.y;
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
                canvas.drawCircle(cx, cy, DOT_RADIUS, dotPaint);
                canvas.drawCircle(cx, cy, DOT_RADIUS + 1, getStrokePaint(0xc0000000, 2));
                canvas.drawCircle(cx, cy, DOT_RADIUS, circlePaint);
                getAndroidCanvas(canvas).drawArc(
                    new RectF(cx - DOT_RADIUS, cy - DOT_RADIUS, cx + DOT_RADIUS, cy + DOT_RADIUS),
                    270, Math.min(360, minSinceReport * 6), false, getAndroidPaint(arcPaint)
                );
                canvas.drawText(label, cx, cy + LABEL_OFFSET, selectedOutlinePaint);
                canvas.drawPath(path, getStrokePaint(0xc0000000, 4));
                canvas.drawPath(path, getStrokePaint(0xffffffff, 2));
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
