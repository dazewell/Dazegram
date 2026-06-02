package com.radolyn.ayugram.chattimezone;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Bottom sheet with a searchable, offset-sorted list of common time zones. */
public final class ChatTimeZonePickerSheet extends BottomSheet {

    public interface Delegate {
        void onTimeZoneChosen(@androidx.annotation.Nullable TimeZone tz);
    }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> filtered = new ArrayList<>();
    private final Adapter adapter;
    private String filter = "";

    private static final String[] CURATED_IDS = {
            "Pacific/Pago_Pago", "Pacific/Honolulu", "America/Anchorage",
            "America/Los_Angeles", "America/Denver", "America/Phoenix",
            "America/Chicago", "America/Mexico_City", "America/New_York",
            "America/Caracas", "America/Halifax", "America/Argentina/Buenos_Aires",
            "America/Sao_Paulo", "America/Noronha", "Atlantic/Cape_Verde",
            "Atlantic/Azores", "UTC", "Europe/London", "Europe/Berlin",
            "Europe/Paris", "Europe/Madrid", "Europe/Rome", "Europe/Athens",
            "Africa/Cairo", "Africa/Johannesburg", "Europe/Istanbul",
            "Europe/Moscow", "Asia/Dubai", "Asia/Tehran", "Asia/Karachi",
            "Asia/Kolkata", "Asia/Kathmandu", "Asia/Dhaka", "Asia/Yangon",
            "Asia/Bangkok", "Asia/Jakarta", "Asia/Singapore", "Asia/Hong_Kong",
            "Asia/Shanghai", "Asia/Taipei", "Asia/Tokyo", "Asia/Seoul",
            "Australia/Perth", "Australia/Adelaide", "Australia/Sydney",
            "Pacific/Guam", "Pacific/Auckland", "Pacific/Fiji", "Pacific/Tongatapu"
    };

    public ChatTimeZonePickerSheet(Context context, Delegate delegate, Theme.ResourcesProvider resourcesProvider) {
        super(context, true, resourcesProvider);
        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        long now = System.currentTimeMillis();
        for (String id : CURATED_IDS) {
            TimeZone tz = TimeZone.getTimeZone(id);
            all.add(new Item(tz, prettyName(id), tz.getOffset(now)));
        }
        Collections.sort(all, (a, b) -> Integer.compare(a.offsetMs, b.offsetMs));
        filtered.addAll(all);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(getString(R.string.ChatTimeZoneTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 4));

        EditText search = new EditText(context);
        search.setHint(getString(R.string.Search));
        search.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        search.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setPadding(0, dp(8), 0, dp(8));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                applyFilter(s.toString());
            }
        });
        FrameLayout searchWrap = new FrameLayout(context);
        searchWrap.setBackground(Theme.createRoundRectDrawable(dp(10), getThemedColor(Theme.key_chat_messagePanelBackground)));
        searchWrap.addView(search, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
        root.addView(searchWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 8));

        RecyclerListView list = new RecyclerListView(context, resourcesProvider);
        list.setLayoutManager(new LinearLayoutManager(context));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= filtered.size()) return;
            Item it = filtered.get(position);
            if (delegate != null) delegate.onTimeZoneChosen(it.tz);
            dismiss();
        });
        root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // "Remove" button
        TextView removeBtn = new TextView(context);
        removeBtn.setText(getString(R.string.ChatTimeZoneRemove));
        removeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        removeBtn.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        removeBtn.setGravity(Gravity.CENTER);
        removeBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
        removeBtn.setOnClickListener(v -> {
            if (delegate != null) delegate.onTimeZoneChosen(null);
            dismiss();
        });
        root.addView(removeBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);
    }

    private void applyFilter(String q) {
        filter = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        if (filter.isEmpty()) {
            filtered.addAll(all);
        } else {
            for (Item it : all) {
                if (it.label.toLowerCase(Locale.ROOT).contains(filter)
                        || it.tz.getID().toLowerCase(Locale.ROOT).contains(filter)) {
                    filtered.add(it);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private static String prettyName(String id) {
        int slash = id.lastIndexOf('/');
        String tail = slash >= 0 ? id.substring(slash + 1) : id;
        return tail.replace('_', ' ');
    }

    private static String formatOffset(int offsetMs) {
        int total = offsetMs / 60000;
        int sign = total < 0 ? -1 : 1;
        int abs = Math.abs(total);
        return String.format(Locale.US, "GMT%s%02d:%02d", sign < 0 ? "-" : "+", abs / 60, abs % 60);
    }

    private static String currentClock(TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static final class Item {
        final TimeZone tz;
        final String label;
        final int offsetMs;
        Item(TimeZone tz, String label, int offsetMs) {
            this.tz = tz; this.label = label; this.offsetMs = offsetMs;
        }
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new Row(parent.getContext()));
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((Row) holder.itemView).bind(filtered.get(position));
        }
        @Override public int getItemCount() { return filtered.size(); }
    }

    private final class Row extends FrameLayout {
        private final TextView name;
        private final TextView meta;
        Row(Context ctx) {
            super(ctx);
            setBackground(Theme.getSelectorDrawable(false));
            setPadding(dp(20), dp(8), dp(20), dp(8));
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            name = new TextView(ctx);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            meta = new TextView(ctx);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            meta.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
            col.addView(name);
            col.addView(meta);
            addView(col, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT));

            TextView clock = new TextView(ctx);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            clock.setTextColor(getThemedColor(Theme.key_chat_status));
            clock.setTypeface(AndroidUtilities.bold());
            clock.setId(android.R.id.text2);
            addView(clock, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.RIGHT));
        }
        void bind(Item it) {
            name.setText(it.label);
            meta.setText(formatOffset(it.offsetMs) + " · " + it.tz.getID());
            ((TextView) findViewById(android.R.id.text2)).setText(currentClock(it.tz));
        }
    }
}
