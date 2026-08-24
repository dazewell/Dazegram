package moe.hx030.momogram.maplibre;

public final class MapStyleFactory {
    public enum RasterStyle {
        OSM(
                new String[]{"https://tile.openstreetmap.org/{z}/{x}/{y}.png"},
                0,
                19,
                512,
                "© OpenStreetMap contributors"
        ),
        WIKIMEDIA(
                new String[]{"https://maps.wikimedia.org/osm-intl/{z}/{x}/{y}.png"},
                0,
                19,
                256,
                "© OpenStreetMap contributors"
        ),
        CARTO_DARK(
                new String[]{
                        "https://cartodb-basemaps-a.global.ssl.fastly.net/dark_all/{z}/{x}/{y}.png",
                        "https://cartodb-basemaps-b.global.ssl.fastly.net/dark_all/{z}/{x}/{y}.png",
                        "https://cartodb-basemaps-c.global.ssl.fastly.net/dark_all/{z}/{x}/{y}.png",
                        "https://cartodb-basemaps-d.global.ssl.fastly.net/dark_all/{z}/{x}/{y}.png"
                },
                0,
                20,
                256,
                "© OpenStreetMap contributors, © CARTO"
        );

        private final String[] tiles;
        private final int minZoom;
        private final int maxZoom;
        private final int tileSize;
        private final String attributionText;

        RasterStyle(String[] tiles, int minZoom, int maxZoom, int tileSize, String attributionText) {
            this.tiles = tiles;
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.tileSize = tileSize;
            this.attributionText = attributionText;
        }

        public String[] getTiles() {
            return tiles;
        }

        public int getMinZoom() {
            return minZoom;
        }

        public int getMaxZoom() {
            return maxZoom;
        }

        public int getTileSize() {
            return tileSize;
        }

        public String getAttributionText() {
            return attributionText;
        }
    }

    public static final String OSM_ATTRIBUTION_HTML = "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors";
    public static final String WIKIMEDIA_ATTRIBUTION_HTML = OSM_ATTRIBUTION_HTML;
    public static final String CARTO_ATTRIBUTION_HTML = "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors, © <a href=\"https://carto.com/attributions\">CARTO</a>";

    private MapStyleFactory() {
    }

    public static String getAttributionHtml(RasterStyle rasterStyle) {
        switch (rasterStyle) {
            case WIKIMEDIA:
                return WIKIMEDIA_ATTRIBUTION_HTML;
            case CARTO_DARK:
                return CARTO_ATTRIBUTION_HTML;
            case OSM:
            default:
                return OSM_ATTRIBUTION_HTML;
        }
    }
}
