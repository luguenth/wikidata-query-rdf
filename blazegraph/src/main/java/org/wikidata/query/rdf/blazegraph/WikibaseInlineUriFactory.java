package org.wikidata.query.rdf.blazegraph;

import org.wikidata.query.rdf.blazegraph.inline.uri.InlineFixedWidthHexIntegerURIHandler;
import org.wikidata.query.rdf.blazegraph.inline.uri.UndecoratedUuidInlineUriHandler;
import org.wikidata.query.rdf.common.uri.CommonValues;
import org.wikidata.query.rdf.common.uri.PropertyType;
import org.wikidata.query.rdf.common.uri.UrisScheme;
import org.wikidata.query.rdf.common.uri.UrisSchemeFactory;

import com.bigdata.rdf.internal.InlineURIFactory;
import com.bigdata.rdf.internal.InlineURIHandler;
import com.bigdata.rdf.internal.InlineUnsignedIntegerURIHandler;
import com.bigdata.rdf.internal.NormalizingInlineUriHandler;
import com.bigdata.rdf.internal.TrailingSlashRemovingInlineUriHandler;

/**
 * Factory building InlineURIHandlers for wikidata.
 *
 * One thing to consider when working on these is that its way better for write
 * (and probably update) performance if all the bits of an entity are grouped
 * together in Blazegraph's BTrees. Scattering them causes updates to have to
 * touch lots of BTree nodes. {s,p,o}, {p,o,s}, and {o,s,p} are the indexes so
 * and {s,p,o} seems most sensitive to scattering.
 *
 * Another thing to consider is that un-inlined uris are stored as longs which
 * take up 9 bytes including the flags byte. And inlined uris are stored as 1
 * flag byte, 1 (or 2) uri prefix bytes, and then delegate date type. That means
 * that if the delegate data type is any larger than 6 bytes then its a net loss
 * on index size using it. So you should avoid longs and uuids. Maybe even
 * forbid them entirely.
 */
public class WikibaseInlineUriFactory extends InlineURIFactory {
    private static final UrisScheme URIS = UrisSchemeFactory.getURISystem();

    public WikibaseInlineUriFactory() {
        /*
         * Order matters here because some of these are prefixes of each other.
         */
        for (PropertyType p: PropertyType.values()) {
            addHandler(new InlineUnsignedIntegerURIHandler(URIS.property(p) + "P"));
        }
        URIS.inlinableEntityInitials().forEach(s -> addHandler(new InlineUnsignedIntegerURIHandler(URIS.entityIdToURI(s))));
        // Lexemes TODO: can't really do it because of Forms: L1-F1

        /*
         * We don't use WikibaseStyleStatementInlineUriHandler because it makes
         * things worse!
         */
        // These aren't part of wikibase but are common in wikidata
        // TODO: add more prefixes?
        // VIAF ID
        InlineURIHandler viaf = new TrailingSlashRemovingInlineUriHandler(
                new InlineUnsignedIntegerURIHandler(CommonValues.VIAF));
        addHandler(viaf);
        addHandler(new NormalizingInlineUriHandler(viaf, CommonValues.VIAF_HTTP));
        // GeoNames ID
        addHandler(new TrailingSlashRemovingInlineUriHandler(
                new InlineUnsignedIntegerURIHandler(CommonValues.GEONAMES)));
        // PubChem ID
        addHandler(new InlineUnsignedIntegerURIHandler(CommonValues.PUBCHEM));
        // ChemSpider ID
        addHandler(new InlineUnsignedIntegerURIHandler(CommonValues.CHEMSPIDER));

        /*
         * Value nodes are inlined even though they are pretty big (uuids). It
         * doesn't seem to effect performance either way.
         *
         * Statements can't be inlined without losing information or making them
         * huge and bloating the index. We could probably rewrite them at the
         * munger into something less-uuid-ish.
         *
         * References aren't uuids - they are sha1s or sha0s or something
         * similarly 160 bit wide. 160 bits is too big to fit into a uuid so we
         * can't inline that without bloating either.
         */
        addHandler(new UndecoratedUuidInlineUriHandler(URIS.value()));
    }

    public static class V001 extends WikibaseInlineUriFactory {
        public V001() {
            super();
            addHandler(new InlineFixedWidthHexIntegerURIHandler(URIS.reference(), 40));
            InlineURIHandler viaf = new TrailingSlashRemovingInlineUriHandler(
                    new InlineUnsignedIntegerURIHandler(CommonValues.VIAF_HTTP));
            addHandler(viaf);
            addHandler(new NormalizingInlineUriHandler(viaf, CommonValues.VIAF));
            // Entrez Gene ID
            addHandler(new InlineUnsignedIntegerURIHandler(CommonValues.GENEID));
        }
    }

    public static class V002 extends V001 {
        public V002() {
            super();
            addHandler(new UndecoratedUuidInlineUriHandler(URIS.wellKnownBNodeIRIPrefix()));
        }

    }
}
