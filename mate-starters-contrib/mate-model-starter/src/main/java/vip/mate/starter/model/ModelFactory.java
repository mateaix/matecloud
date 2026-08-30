/*
 * Copyright (c) 2024-2026 Beijing Daotiandi Technology Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package vip.mate.starter.model;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import vip.mate.base.model.ModelEndpoint;

/**
 * Builds Spring AI model clients from a resolved {@link ModelEndpoint}.
 *
 * <p>Both local-direct and via-gateway supply modes speak the OpenAI-compatible
 * protocol — they differ only in {@code base_url} / API key — so a single
 * construction path per modality serves both. {@code chat} / {@code embedding} /
 * {@code speech} are wired today; {@code rerank} / {@code stt} / {@code image} /
 * {@code video} remain TODO (vendor-specific clients).
 *
 * @author mateaix
 */
public class ModelFactory {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /**
     * Build a {@link ChatModel} for an LLM endpoint.
     *
     * @param ep resolved endpoint (LOCAL or GATEWAY; both OpenAI-compatible)
     * @return a ready-to-call chat model
     */
    public ChatModel chatModel(ModelEndpoint ep) {
        ModelEndpoint e = require(ep, "LLM");
        // Spring AI 2.x: 凭据落在 Options, 由 OpenAiSetup 经官方 com.openai SDK 构造客户端
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        options.model(e.model());
        options.apiKey(apiKey(e));
        options.baseUrl(baseUrl(e));

        return OpenAiChatModel.builder()
                .options(options.build())
                .build();
    }

    /**
     * Build an {@link EmbeddingModel} for an EMBEDDING endpoint (OpenAI-compatible).
     *
     * @param ep resolved endpoint
     * @return a ready-to-call embedding model
     */
    public EmbeddingModel embeddingModel(ModelEndpoint ep) {
        ModelEndpoint e = require(ep, "EMBEDDING");
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder();
        options.model(e.model());
        options.apiKey(apiKey(e));
        options.baseUrl(baseUrl(e));

        return OpenAiEmbeddingModel.builder()
                .metadataMode(MetadataMode.EMBED)
                .options(options.build())
                .build();
    }

    /**
     * Build a text-to-speech model for a TTS endpoint (OpenAI-compatible audio API).
     *
     * @param ep resolved endpoint
     * @return a ready-to-call speech model
     */
    public OpenAiAudioSpeechModel speechModel(ModelEndpoint ep) {
        ModelEndpoint e = require(ep, "TTS");
        OpenAiAudioSpeechOptions.Builder options = OpenAiAudioSpeechOptions.builder();
        options.model(e.model());
        options.apiKey(apiKey(e));
        options.baseUrl(baseUrl(e));

        return OpenAiAudioSpeechModel.builder()
                .options(options.build())
                .build();
    }

    /** Convenience alias for {@link #speechModel(ModelEndpoint)}. */
    public OpenAiAudioSpeechModel tts(ModelEndpoint ep) {
        return speechModel(ep);
    }

    // TODO(P2): rerank  — needs a vendor rerank API (no Spring AI OpenAI client yet).
    // TODO(P2): stt     — OpenAiAudioTranscriptionModel (OpenAI-compatible) can be wired similarly.
    // TODO(P2): image   — OpenAiImageModel (OpenAI-compatible) can be wired similarly.
    // TODO(P2): video   — no OpenAI-compatible client; vendor-specific (out of scope here).

    private static ModelEndpoint require(ModelEndpoint ep, String type) {
        if (ep == null) {
            throw new IllegalStateException("No " + type + " endpoint configured");
        }
        return ep;
    }

    private static String baseUrl(ModelEndpoint e) {
        return (e.baseUrl() == null || e.baseUrl().isBlank()) ? DEFAULT_BASE_URL : e.baseUrl();
    }

    private static String apiKey(ModelEndpoint e) {
        return e.apiKey() == null ? "" : e.apiKey();
    }
}
