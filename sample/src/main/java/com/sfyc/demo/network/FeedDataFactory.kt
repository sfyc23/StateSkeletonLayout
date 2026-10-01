package com.sfyc.demo.network

object FeedDataFactory {
    fun records(revision: Int): List<FeedRecord> = buildList {
        // 首屏同时覆盖四种业务记录；装饰项稍后插入。
        add(FeedRecord.Profile(1, revision))
        add(FeedRecord.Tile(1, revision))
        add(FeedRecord.Tile(2, revision))
        add(FeedRecord.Article(1, revision))
        add(FeedRecord.Carousel(1, revision, (1..6).map {
            CarouselChild("carousel-001-child-$it", it)
        }))
        add(FeedRecord.Profile(2, revision))
        add(FeedRecord.Article(2, revision))
        add(FeedRecord.Tile(3, revision))
        add(FeedRecord.Profile(3, revision))
        add(FeedRecord.Profile(4, revision))
        (5..12).forEach { add(FeedRecord.Profile(it, revision)) }
        (3..6).forEach { add(FeedRecord.Article(it, revision)) }
        (4..8).forEach { add(FeedRecord.Tile(it, revision)) }
    }

    fun items(records: List<FeedRecord>, includeDecorations: Boolean): List<FeedItem> {
        if (records.isEmpty()) return emptyList()
        return buildList {
            if (includeDecorations) {
                add(FeedItem.Banner(records.first().revision))
                add(FeedItem.Section(1))
            }
            records.forEachIndexed { index, record ->
                if (includeDecorations && index == 3) add(FeedItem.Section(2))
                add(when (record) {
                    is FeedRecord.Profile -> FeedItem.Profile(record)
                    is FeedRecord.Article -> FeedItem.Article(record)
                    is FeedRecord.Tile -> FeedItem.Tile(record)
                    is FeedRecord.Carousel -> FeedItem.Carousel(record.copy(children = record.children.toList()))
                })
            }
        }
    }
}
