package ca.zesty.fleetreceiver;

import android.arch.persistence.db.SupportSQLiteDatabase;
import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.arch.persistence.room.migration.Migration;
import android.content.Context;

@Database(entities = {
    MobileNumberEntity.class,
    ReporterEntity.class,
    PointEntity.class,
    SourceEntity.class,
    TargetEntity.class
}, exportSchema = false, version = 5)
public abstract class AppDatabase extends RoomDatabase {
    public abstract MobileNumberDao getMobileNumberDao();
    public abstract ReporterDao getReporterDao();
    public abstract PointDao getPointDao();
    public abstract SourceDao getSourceDao();
    public abstract TargetDao getTargetDao();

    public static AppDatabase getDatabase(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, "database")
            .addMigrations(MIGRATION_4_TO_5)
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build();
    }

    static final Migration MIGRATION_4_TO_5 = new Migration(4, 5) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("alter table points add column parent_source_id text;");
            db.execSQL("alter table reporters add column source_id text;");
            db.execSQL("create table if not exists sources (" +
                "source_id text not null, " +
                "mobile_number text, " +
                "label text, " +
                "activation_millis integer, " +
                "primary key (source_id)" +
            ");");
            db.execSQL("create table if not exists targets (" +
                "target_id text not null, " +
                "mobile_number text, " +
                "label text, " +
                "activation_millis integer, " +
                "primary key (target_id)" +
            ");");
        }
    };
}
