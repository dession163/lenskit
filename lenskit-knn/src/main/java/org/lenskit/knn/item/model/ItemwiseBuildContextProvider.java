/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2016 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.knn.item.model;

import com.google.common.base.Stopwatch;
import it.unimi.dsi.fastutil.longs.*;
import org.grouplens.lenskit.vectors.MutableSparseVector;
import org.grouplens.lenskit.vectors.SparseVector;
import org.grouplens.lenskit.vectors.VectorEntry;
import org.lenskit.baseline.ItemMeanRatingItemScorer;
import org.lenskit.data.dao.DataAccessObject;
import org.lenskit.data.entities.CommonAttributes;
import org.lenskit.data.ratings.Rating;
import org.lenskit.data.ratings.Ratings;
import org.lenskit.inject.Transient;
import org.lenskit.transform.normalize.ItemVectorNormalizer;
import org.lenskit.transform.normalize.MeanCenteringVectorNormalizer;
import org.lenskit.util.IdBox;
import org.lenskit.util.collections.LongUtils;
import org.lenskit.util.io.ObjectStream;
import org.lenskit.util.keys.SortedKeyIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.List;

/**
 * Builder for {@link ItemItemBuildContext} that normalizes per-item, not per-user.  More efficient
 * when using e.g. item-based normalization.  Right now it only works for rating data.
 *
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 */
public class ItemwiseBuildContextProvider implements Provider<ItemItemBuildContext> {
    private static final Logger logger = LoggerFactory.getLogger(ItemwiseBuildContextProvider.class);

    private final DataAccessObject dao;
    private final ItemVectorNormalizer normalizer;

    /**
     * Construct a new build context provider.
     * @param dao  The data access object.
     * @param norm The item vector normalizer.  This is applied to item rating vectors.  You should
     *             take care to use a compatible normalizer for the item scorer (e.g. if this uses
     *             a {@link MeanCenteringVectorNormalizer},
     *             then you should use {@link ItemMeanRatingItemScorer}
     *             for the user vector normalization in the scorer).
     */
    @Inject
    public ItemwiseBuildContextProvider(@Transient DataAccessObject dao,
                                        @Transient ItemVectorNormalizer norm) {
        this.dao = dao;
        normalizer = norm;
    }

    /**
     * Constructs and returns a new ItemItemBuildContext.
     *
     * @return a new ItemItemBuildContext.
     */
    @Override
    public ItemItemBuildContext get() {
        logger.info("constructing build context");
        Stopwatch timer = Stopwatch.createStarted();
        logger.debug("using normalizer {}", normalizer);

        logger.debug("Building item data");
        Long2ObjectMap<LongList> userItems = new Long2ObjectOpenHashMap<>(1000);
        Long2ObjectMap<SparseVector> itemVectors = new Long2ObjectOpenHashMap<>(1000);
        ObjectStream<IdBox<List<Rating>>> itemObjectStream = dao.query(Rating.class)
                                                                .groupBy(CommonAttributes.ITEM_ID)
                                                                .stream();
        try {
            for (IdBox<List<Rating>> itemRatings: itemObjectStream) {
                long item = itemRatings.getId();
                List<Rating> ratings = itemRatings.getValue();
                if (logger.isTraceEnabled()) {
                    logger.trace("processing {} ratings for item {}", ratings.size(), item);
                }
                MutableSparseVector vector = MutableSparseVector.create(Ratings.itemRatingVector(ratings));
                normalizer.normalize(item, vector, vector);
                for (VectorEntry e: vector) {
                    long user = e.getKey();
                    LongList uis = userItems.get(user);
                    if (uis == null) {
                        // lists are nice and fast, we only see each item once
                        uis = new LongArrayList();
                        userItems.put(user, uis);
                    }
                    uis.add(item);
                }
                itemVectors.put(item, vector.freeze());
            }
        } finally {
            itemObjectStream.close();
        }

        Long2ObjectMap<LongSortedSet> userItemSets = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<LongList> entry: userItems.long2ObjectEntrySet()) {
            userItemSets.put(entry.getLongKey(), LongUtils.packedSet(entry.getValue()));
        }

        SortedKeyIndex items = SortedKeyIndex.fromCollection(itemVectors.keySet());
        SparseVector[] itemData = new SparseVector[items.size()];
        for (int i = 0; i < itemData.length; i++) {
            long itemId = items.getKey(i);
            itemData[i] = itemVectors.get(itemId);
        }

        timer.stop();
        logger.info("finished build context for {} items in {}", items.size(), timer);
        return new ItemItemBuildContext(items, itemData, userItemSets);
    }
}
