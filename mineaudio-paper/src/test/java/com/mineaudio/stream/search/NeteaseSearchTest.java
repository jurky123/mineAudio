package com.mineaudio.stream.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mineaudio.stream.resolve.ResolveException;

class NeteaseSearchTest {

    @Test
    void parseSearchMapsFieldsAndPlayability() throws Exception {
        String body = """
                {"result":{"songs":[
                  {"id":1,"name":"晴天","artists":[{"name":"周杰伦"}],"duration":269000,"fee":8},
                  {"id":2,"name":"VIP曲","artists":[{"name":"某人"}],"duration":100000,"fee":1},
                  {"id":3,"name":"付费曲","artists":[{"name":"某人"}],"duration":100000,"fee":4}
                ]}}""";
        List<SearchResult> results = NeteaseSearch.parseSearch(body);
        assertEquals(3, results.size());
        assertEquals("晴天", results.get(0).title());
        assertEquals("周杰伦", results.get(0).artist());
        assertEquals(269000, results.get(0).durationMs());
        assertTrue(results.get(0).playable());
        assertEquals("标准音质", results.get(0).note());
        assertTrue(results.get(1).playable());
        assertEquals("VIP", results.get(1).note());
        assertFalse(results.get(2).playable());
        assertEquals("需购买", results.get(2).note());
        assertNull(results.get(0).coverUrl());
    }

    @Test
    void mergeDetailAddsCoverAndNormalizesTitleArtist() {
        List<SearchResult> base = List.of(
                new SearchResult("ncmlite", "1", "旧标题", "旧歌手", null, 1000, true, "标准音质"));
        String detail = """
                {"songs":[{"id":1,"name":"新标题","ar":[{"name":"新歌手"}],
                  "al":{"picUrl":"https://p2.music.126.net/x/1.jpg"}}]}""";
        List<SearchResult> merged = NeteaseSearch.mergeDetail(base, detail);
        assertEquals(1, merged.size());
        assertEquals("新标题", merged.get(0).title());
        assertEquals("新歌手", merged.get(0).artist());
        assertEquals("https://p2.music.126.net/x/1.jpg", merged.get(0).coverUrl());
        assertTrue(merged.get(0).playable());
    }

    @Test
    void detailParamsEscapesInnerQuotes() {
        List<SearchResult> base = List.of(
                new SearchResult("ncmlite", "1", "a", "b", null, 0, true, "可播放"),
                new SearchResult("ncmlite", "2", "c", "d", null, 0, true, "可播放"));
        assertEquals("{\"c\":\"[{\\\"id\\\":\\\"1\\\"},{\\\"id\\\":\\\"2\\\"}]\"}",
                NeteaseSearch.detailParams(base));
    }

    @Test
    void parseSearchRejectsNonJson() {
        try {
            NeteaseSearch.parseSearch("not-json");
            org.junit.jupiter.api.Assertions.fail("应当抛出 ResolveException");
        } catch (ResolveException expected) {
            // 预期
        }
    }
}
