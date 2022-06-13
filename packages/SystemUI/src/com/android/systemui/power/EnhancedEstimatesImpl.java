package com.android.systemui.power;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.fuelgauge.EstimateKt;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.dagger.SysUISingleton;

import java.time.Duration;

import javax.inject.Inject;

@SysUISingleton
public class EnhancedEstimatesImpl implements EnhancedEstimates {

    private static final String TAG = "EnhancedEstimatesImpl";

    private Context mContext;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    @Inject
    public EnhancedEstimatesImpl(Context context) {
        mContext = context;
    }

    @Override
    public boolean isHybridNotificationEnabled() {
        try {
            if (!mContext.getPackageManager()
                    .getPackageInfo(
                            "com.google.android.apps.turbo",
                            PackageManager.MATCH_DISABLED_COMPONENTS)
                    .applicationInfo
                    .enabled) {
                return false;
            }
            updateFlags();
            return mParser.getBoolean("hybrid_enabled", true);
        } catch (PackageManager.NameNotFoundException unused) {
            return false;
        }
    }

    @Override
    public Estimate getEstimate() {
        Estimate turboEstimate = getTurboEstimate();
        if (turboEstimate.getEstimateMillis() > 0) {
            return turboEstimate;
        }
        return getFrameworkEstimate();
    }

    private Estimate getTurboEstimate() {
        Uri uri =
                new Uri.Builder()
                        .scheme("content")
                        .authority("com.google.android.apps.turbo.estimated_time_remaining")
                        .appendPath("time_remaining")
                        .build();

        try (Cursor query = mContext.getContentResolver().query(uri, null, null, null, null)) {
            if (query == null) {
                Log.d(TAG, "Turbo estimate query returned null cursor");
                return getUnknownEstimate();
            }

            if (!query.moveToFirst()) {
                Log.d(TAG, "Turbo estimate query returned no rows");
                return getUnknownEstimate();
            }

            int estimateColumn = query.getColumnIndex("battery_estimate");
            if (estimateColumn == -1) {
                Log.d(TAG, "Turbo estimate query missing battery_estimate column");
                return getUnknownEstimate();
            }

            long estimateMillis = query.getLong(estimateColumn);
            if (estimateMillis <= 0) {
                Log.d(TAG, "Turbo estimate query returned invalid estimate: " + estimateMillis);
                return getUnknownEstimate();
            }

            long timeRemaining = EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN;
            boolean isBasedOnUsage = true;
            int basedOnUsageColumn = query.getColumnIndex("is_based_on_usage");
            if (basedOnUsageColumn != -1 && query.getInt(basedOnUsageColumn) == 0) {
                isBasedOnUsage = false;
            }
            int averageBatteryLifeColumn = query.getColumnIndex("average_battery_life");
            if (averageBatteryLifeColumn != -1) {
                long averageBatteryLife = query.getLong(averageBatteryLifeColumn);
                if (averageBatteryLife != EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN) {
                    long duration = Duration.ofMinutes(15L).toMillis();
                    if (Duration.ofMillis(averageBatteryLife).compareTo(Duration.ofDays(1L))
                            >= 0) {
                        duration = Duration.ofHours(1L).toMillis();
                    }
                    timeRemaining =
                            PowerUtil.roundTimeToNearestThreshold(averageBatteryLife, duration);
                }
            }

            return new Estimate(estimateMillis, isBasedOnUsage, timeRemaining);
        } catch (Exception exception) {
            Log.d(TAG, "Something went wrong when getting an estimate from Turbo", exception);
        }
        return getUnknownEstimate();
    }

    private Estimate getFrameworkEstimate() {
        BatteryStatsManager batteryStatsManager =
                mContext.getSystemService(BatteryStatsManager.class);
        if (batteryStatsManager == null) {
            Log.d(TAG, "BatteryStatsManager unavailable for framework estimate");
            return getUnknownEstimate();
        }

        try (BatteryUsageStats batteryUsageStats = batteryStatsManager.getBatteryUsageStats()) {
            long estimateMillis = batteryUsageStats.getBatteryTimeRemainingMs();
            if (estimateMillis > 0) {
                return new Estimate(
                        estimateMillis,
                        false /* isBasedOnUsage */,
                        EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN);
            }
            Log.d(TAG, "Framework estimate returned invalid estimate: " + estimateMillis);
        } catch (Exception exception) {
            Log.d(TAG, "Something went wrong when getting a framework estimate", exception);
        }
        return getUnknownEstimate();
    }

    private Estimate getUnknownEstimate() {
        return new Estimate(
                EstimateKt.ESTIMATE_MILLIS_UNKNOWN,
                false /* isBasedOnUsage */,
                EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN);
    }

    @Override
    public long getLowWarningThreshold() {
        updateFlags();
        return mParser.getLong("low_threshold", Duration.ofHours(3L).toMillis());
    }

    @Override
    public long getSevereWarningThreshold() {
        updateFlags();
        return mParser.getLong("severe_threshold", Duration.ofHours(1L).toMillis());
    }

    @Override
    public boolean getLowWarningEnabled() {
        updateFlags();
        return mParser.getBoolean("low_warning_enabled", false);
    }

    private void updateFlags() {
        try {
            mParser.setString(
                    Settings.Global.getString(
                            mContext.getContentResolver(), "hybrid_sysui_battery_warning_flags"));
        } catch (IllegalArgumentException unused) {
            Log.e("EnhancedEstimates", "Bad hybrid sysui warning flags");
        }
    }
}
