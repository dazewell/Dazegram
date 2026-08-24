package moe.hx030.momogram.maplibre;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.maplibre.android.geometry.LatLng;

public final class MapPin {
    private final MapLibreView owner;
    private final ImageView view;
    private LatLng position;
    private float anchorU;
    private float anchorV;

    MapPin(@NonNull MapLibreView owner, @NonNull Drawable icon, @NonNull LatLng position, float anchorU, float anchorV, @Nullable View.OnClickListener listener) {
        this.owner = owner;
        this.position = position;
        this.anchorU = anchorU;
        this.anchorV = anchorV;
        this.view = new ImageView(owner.getContext());
        this.view.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        this.view.setImageDrawable(icon);
        this.view.setOnClickListener(listener);
    }

    ImageView getView() {
        return view;
    }

    public LatLng getPosition() {
        return position;
    }

    public void setPosition(@NonNull LatLng position) {
        this.position = position;
        owner.updatePin(this);
    }

    public void setIcon(@NonNull Drawable icon) {
        view.setImageDrawable(icon);
        owner.updatePin(this);
    }

    public void setAnchor(float anchorU, float anchorV) {
        this.anchorU = anchorU;
        this.anchorV = anchorV;
        owner.updatePin(this);
    }

    public void setRotation(float rotation) {
        view.setRotation(rotation);
    }

    public void remove() {
        owner.removePin(this);
    }

    float getAnchorU() {
        return anchorU;
    }

    float getAnchorV() {
        return anchorV;
    }
}
