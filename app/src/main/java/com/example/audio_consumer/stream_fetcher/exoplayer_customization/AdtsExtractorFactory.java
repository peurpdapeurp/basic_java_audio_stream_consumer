package com.example.audio_consumer.stream_fetcher.exoplayer_customization;

import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.AdtsExtractor;

public final class AdtsExtractorFactory implements ExtractorsFactory {

    private boolean constantBitrateSeekingEnabled;
    private @AdtsExtractor.Flags int adtsFlags;

    /**
     * Convenience method to set whether approximate seeking using constant bitrate assumptions should
     * be enabled for all extractors that support it. If set to true, the flags required to enable
     * this functionality will be OR'd with those passed to the setters when creating extractor
     * instances. If set to false then the flags passed to the setters will be used without
     * modification.
     *
     * @param constantBitrateSeekingEnabled Whether approximate seeking using a constant bitrate
     *     assumption should be enabled for all extractors that support it.
     * @return The factory, for convenience.
     */
    public synchronized AdtsExtractorFactory setConstantBitrateSeekingEnabled(
            boolean constantBitrateSeekingEnabled) {
        this.constantBitrateSeekingEnabled = constantBitrateSeekingEnabled;
        return this;
    }

    /**
     * Sets flags for {@link AdtsExtractor} instances created by the factory.
     *
     * @see AdtsExtractor#AdtsExtractor(long, int)
     * @param flags The flags to use.
     * @return The factory, for convenience.
     */
    public synchronized AdtsExtractorFactory setAdtsExtractorFlags(
            @AdtsExtractor.Flags int flags) {
        this.adtsFlags = flags;
        return this;
    }

    @Override
    public synchronized Extractor[] createExtractors() {
        Extractor[] extractors = new Extractor[1];
        extractors[0] =
                new AdtsExtractor(
                        /* firstStreamSampleTimestampUs= */ 0,
                        adtsFlags
                                | (constantBitrateSeekingEnabled
                                ? AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
                                : 0));
        return extractors;
    }

}
