package com.cleanyourcar.launcher;

import java.util.Calendar;
import java.util.Date;

/**
 * Formats Vietnamese date strings consistently across Android 6.0+ locales.
 */
public final class VietnameseDateFormatter {
    private static final String[] DAYS = new String[] {
            "", "Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"
    };

    private VietnameseDateFormatter() {
    }

    public static String formatToday(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date != null ? date : new Date());
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int month = cal.get(Calendar.MONTH) + 1;
        String dayName = (dow >= 1 && dow < DAYS.length) ? DAYS[dow] : "";
        return dayName + ", " + day + " Tháng " + month;
    }
}
