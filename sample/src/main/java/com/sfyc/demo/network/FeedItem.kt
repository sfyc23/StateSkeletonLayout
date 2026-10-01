package com.sfyc.demo.network

import java.util.Locale

/** 业务记录与装饰模型分开计数，稳定身份与页号、请求号无关。 */
sealed interface FeedRecord {
    val stableId: String
    val number: Int
    val revision: Int

    data class Profile(override val number: Int, override val revision: Int) : FeedRecord {
        override val stableId = "profile-%03d".format(Locale.ROOT, number)
    }

    data class Article(override val number: Int, override val revision: Int) : FeedRecord {
        override val stableId = "article-%03d".format(Locale.ROOT, number)
    }

    data class Tile(override val number: Int, override val revision: Int) : FeedRecord {
        override val stableId = "tile-%03d".format(Locale.ROOT, number)
    }

    data class Carousel(
        override val number: Int,
        override val revision: Int,
        val children: List<CarouselChild>,
    ) : FeedRecord {
        override val stableId = "carousel-%03d".format(Locale.ROOT, number)
    }
}

data class CarouselChild(val stableId: String, val number: Int)

sealed interface FeedItem {
    val stableId: String

    data class Banner(val revision: Int) : FeedItem {
        override val stableId = "banner-feed"
    }

    data class Section(val group: Int) : FeedItem {
        override val stableId = "section-$group"
    }

    data class Profile(val record: FeedRecord.Profile) : FeedItem {
        override val stableId get() = record.stableId
    }

    data class Article(val record: FeedRecord.Article) : FeedItem {
        override val stableId get() = record.stableId
    }

    data class Tile(val record: FeedRecord.Tile) : FeedItem {
        override val stableId get() = record.stableId
    }

    data class Carousel(val record: FeedRecord.Carousel) : FeedItem {
        override val stableId get() = record.stableId
    }
}
