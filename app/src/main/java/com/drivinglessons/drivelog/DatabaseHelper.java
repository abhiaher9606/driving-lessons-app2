package com.drivinglessons.drivelog;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "DrivingLessons.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_PAYMENTS = "payments";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_CLIENT_NAME = "client_name";
    public static final String COLUMN_AMOUNT = "amount";
    public static final String COLUMN_STATUS = "status";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_PAYMENTS_TABLE = "CREATE TABLE " + TABLE_PAYMENTS + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_CLIENT_NAME + " TEXT, "
                + COLUMN_AMOUNT + " REAL, "
                + COLUMN_STATUS + " TEXT)";
        db.execSQL(CREATE_PAYMENTS_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PAYMENTS);
        onCreate(db);
    }

    // Insert a new payment record
    public boolean insertPayment(String clientName, double amount, String status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_CLIENT_NAME, clientName);
        values.put(COLUMN_AMOUNT, amount);
        values.put(COLUMN_STATUS, status);

        long result = db.insert(TABLE_PAYMENTS, null, values);
        db.close();
        return result != -1;
    }

    // Update existing client payment
    public boolean updatePaymentRecord(int id, double amount, String status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_AMOUNT, amount);
        values.put(COLUMN_STATUS, status);

        int rows = db.update(TABLE_PAYMENTS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
        db.close();
        return rows > 0;
    }
}
