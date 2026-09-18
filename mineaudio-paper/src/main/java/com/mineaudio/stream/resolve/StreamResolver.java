package com.mineaudio.stream.resolve;

import java.util.concurrent.CompletionStage;

/** 音源解析器：把 source+id 解析为可播放 URL（设计文档 Phase 8）。 */
public interface StreamResolver {

    String id();

    boolean supports(ResolveRequest request);

    CompletionStage<ResolveResult> resolve(ResolveRequest request);
}
