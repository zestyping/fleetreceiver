package ca.zesty.fleetreceiver;

import android.arch.persistence.db.SupportSQLiteDatabase;
import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.arch.persistence.room.migration.Migration;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

@Database(entities = {
    MobileNumberEntity.class,
    ReporterEntity.class,
    PointEntity.class,
    SourceEntity.class,
    TargetEntity.class
}, exportSchema = false, version = 6)
public abstract class AppDatabase extends RoomDatabase {
    public abstract MobileNumberDao getMobileNumberDao();
    public abstract ReporterDao getReporterDao();
    public abstract PointDao getPointDao();
    public abstract SourceDao getSourceDao();
    public abstract TargetDao getTargetDao();

    public static AppDatabase getDatabase(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, "database")
            .addMigrations(MIGRATION_5_TO_6)
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build();
    }

    static final Migration MIGRATION_5_TO_6 = new Migration(5, 6) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("alter table points add column parent_source_id text;");
            db.execSQL("alter table reporters add column source_id text;");
            db.execSQL("create table if not exists sources (" +
                "source_id text not null, " +
                "label text, " +
                "activation_millis integer, " +
                "primary key (source_id)" +
            ");");
            db.execSQL("create table if not exists targets (" +
                "target_id text not null, " +
                "label text, " +
                "activation_millis integer, " +
                "primary key (target_id)" +
            ");");
        }
    };

    /** Deactivates the reporter associated with a given mobile number. */
    public void deactivateReporterByNumber(String number) {
        MobileNumberEntity mobileNumber = getMobileNumberDao().get(number);
        if (mobileNumber != null && mobileNumber.reporterId != null) {
            ReporterEntity reporter = getReporterDao().get(mobileNumber.reporterId);
            if (reporter != null) {
                reporter.activationMillis = null;
                getReporterDao().put(reporter);
            }
        }
    }

    public List<String> getNumbers(List<MobileNumberEntity> mobileNumbers) {
        List<String> numbers = new ArrayList<>();
        for (MobileNumberEntity mobileNumber : mobileNumbers) {
            numbers.add(mobileNumber.number);
        }
        return numbers;
    }

    public List<String> getReporterNumbers(String reporterId) {
        return getNumbers(getMobileNumberDao().getAllByReporterId(reporterId));
    }

    public List<String> getReceiverNumbers(String receiverId) {
        return getNumbers(getMobileNumberDao().getAllByReceiverId(receiverId));
    }
    
    public String getReceiverNumber(String receiverId) {
        List<String> numbers = getReceiverNumbers(receiverId);
        return numbers.isEmpty() ? null : numbers.get(0);
    }
}
