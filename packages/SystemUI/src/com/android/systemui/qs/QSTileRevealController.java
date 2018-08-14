package com.android.systemui.qs;

import static com.android.systemui.Prefs.Key.QS_TILE_SPECS_REVEALED;

import android.content.Context;
import android.os.Handler;
import android.util.ArraySet;

import com.android.systemui.Prefs;
import com.android.systemui.plugins.qs.QSTile;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class QSTileRevealController {
    private static final long QS_REVEAL_TILES_DELAY = 500L;

    private final Context mContext;
    private final QSPanel mQSPanel;
    private final PagedTileLayout mPagedTileLayout;
    private final ArraySet<String> mTilesToReveal = new ArraySet<>();
    private final Handler mHandler = new Handler();

    private final Runnable mRevealQsTiles = new Runnable() {
        @Override
        public void run() {
            mPagedTileLayout.startTileReveal(mTilesToReveal, () -> {
                if (mQSPanel.isExpanded()) {
                    addTileSpecsToRevealed(mTilesToReveal);
                    mTilesToReveal.clear();
                }
            });
        }
    };

    QSTileRevealController(Context context, QSPanel qsPanel, PagedTileLayout pagedTileLayout) {
        mContext = context;
        mQSPanel = qsPanel;
        mPagedTileLayout = pagedTileLayout;
    }

    public void setExpansion(float expansion) {
        if (expansion == 1f) {
            mHandler.postDelayed(mRevealQsTiles, QS_REVEAL_TILES_DELAY);
        } else {
            mHandler.removeCallbacks(mRevealQsTiles);
        }
    }

    public void updateRevealedTiles(Collection<QSTile> tiles) {
        ArraySet<String> tileSpecs = new ArraySet<>();
        for (QSTile tile : tiles) {
            tileSpecs.add(tile.getTileSpec());
        }

        final Set<String> revealedTiles = Prefs.getStringSet(
                mContext, QS_TILE_SPECS_REVEALED, Collections.EMPTY_SET);
        if (revealedTiles.isEmpty() || mQSPanel.isShowingCustomize()) {
            // Do not reveal QS tiles the user has upon first load or those that they directly
            // added through customization.
            addTileSpecsToRevealed(tileSpecs);
        } else {
            // Animate all tiles that the user has not directly added themselves.
            tileSpecs.removeAll(revealedTiles);
            mTilesToReveal.addAll(tileSpecs);
        }
    }

    private void addTileSpecsToRevealed(ArraySet<String> specs) {
        final ArraySet<String> revealedTiles = new ArraySet<>(
                Prefs.getStringSet(mContext, QS_TILE_SPECS_REVEALED, Collections.EMPTY_SET));
        revealedTiles.addAll(specs);
        Prefs.putStringSet(mContext, QS_TILE_SPECS_REVEALED, revealedTiles);
    }
}
