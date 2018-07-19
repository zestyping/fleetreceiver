package ca.zesty.fleetreceiver;

import android.text.InputFilter;
import android.text.Spanned;

class PrintableAsciiFilter implements InputFilter {
    @Override public CharSequence filter(
        CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (!(c >= 32 && c <= 126)) return "";
        }
        return null;
    }
}
