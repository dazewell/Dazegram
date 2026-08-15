package tw.nekomimi.nekogram.folder;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ExtendedGridLayoutManager;
import org.telegram.ui.Components.RecyclerListView;

public class IconSelectorAlert {

    /**
     * How a grid cell turns a stored icon key into what it draws and announces. Lets a caller with
     * its own icon namespace reuse this grid without the folder set having to know about it.
     */
    public interface IconResolver {
        int drawableFor(String key);

        @Nullable
        default CharSequence nameFor(String key) {
            return null;
        }
    }

    private static final IconResolver FOLDER_ICONS = FolderIconHelper::getTabIcon;

    public static void show(BaseFragment fragment, OnIconSelectedListener onIconSelectedListener) {
        Context context = fragment.getParentActivity();
        AlertDialog.Builder builder = build(context, folderKeys(), FOLDER_ICONS, onIconSelectedListener);
        fragment.showDialog(builder.create());
    }

    /**
     * Context-only variant for a caller that is itself showing a dialog (e.g. an inline icon
     * button inside an add/edit AlertDialog) -- BaseFragment.showDialog() unconditionally dismisses
     * whatever dialog is currently visible before installing the new one, which would tear down the
     * caller's own dialog out from under it. This shows the grid as a plain AlertDialog instead, so
     * the caller's dialog is untouched and still on screen underneath.
     */
    public static AlertDialog show(Context context, OnIconSelectedListener onIconSelectedListener) {
        return show(context, folderKeys(), FOLDER_ICONS, onIconSelectedListener);
    }

    /**
     * Same grid over a caller-supplied ordered key list and resolver, so a feature can lead with
     * its own glyphs and still offer the folder set behind them.
     */
    public static AlertDialog show(Context context, List<String> keys, IconResolver resolver, OnIconSelectedListener onIconSelectedListener) {
        AlertDialog.Builder builder = build(context, keys, resolver, onIconSelectedListener);
        AlertDialog dialog = builder.create();
        dialog.show();
        return dialog;
    }

    private static List<String> folderKeys() {
        return new ArrayList<>(FolderIconHelper.folderIcons.keySet());
    }

    private static AlertDialog.Builder build(Context context, List<String> keys, IconResolver resolver, OnIconSelectedListener onIconSelectedListener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        GridAdapter gridAdapter = new GridAdapter(keys, resolver);
        RecyclerListView recyclerListView = new RecyclerListView(context);
        recyclerListView.setClipToPadding(false);
        recyclerListView.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        recyclerListView.setLayoutManager(new ExtendedGridLayoutManager(recyclerListView.getContext(), 6));
        recyclerListView.setAdapter(gridAdapter);
        recyclerListView.setSelectorDrawableColor(0);
        recyclerListView.setOnItemClickListener((view, position) -> {
            onIconSelectedListener.onIconSelected((String) view.getTag());
            builder.getDismissRunnable().run();
        });

        builder.setView(recyclerListView);
        return builder;
    }

    private static class GridAdapter extends RecyclerListView.SelectionAdapter {
        private final String[] icons;
        private final IconResolver resolver;

        GridAdapter(List<String> keys, IconResolver resolver) {
            this.icons = keys.toArray(new String[0]);
            this.resolver = resolver;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            var view = new AppCompatImageView(parent.getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int iconSize = MeasureSpec.makeMeasureSpec(parent.getMeasuredWidth() / 6, MeasureSpec.EXACTLY);
                    super.onMeasure(iconSize, iconSize);
                }
            };
            view.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), AndroidUtilities.dp(2), AndroidUtilities.dp(2)));
            view.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            view.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            var imageView = (ImageView) holder.itemView;
            imageView.setTag(icons[position]);
            imageView.setImageResource(resolver.drawableFor(icons[position]));
            imageView.setContentDescription(resolver.nameFor(icons[position]));
        }

        @Override
        public int getItemCount() {
            return icons.length;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }
    }

    public interface OnIconSelectedListener {
        void onIconSelected(String emoticon);
    }
}
