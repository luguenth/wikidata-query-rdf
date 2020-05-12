package org.wikidata.query.rdf.blazegraph;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.AfterClass;
import org.wikidata.query.rdf.blazegraph.inline.literal.WKTSerializer;
import org.wikidata.query.rdf.common.uri.GeoSparql;

import com.bigdata.bop.engine.QueryEngine;
import com.bigdata.bop.fed.QueryEngineFactory;
import com.bigdata.journal.TemporaryStore;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.TempTripleStore;

/**
 * Base class for tests that create a triple store.
 *
 * <p>
 * We have to take a number of actions to make RandomizedRunner compatible with
 * Blazegraph:
 * <ul>
 * <li>Switch the ThreadLeakScope to SUITE because there are threads that
 * survive across tests
 * <li>Create a temporary store that is shared for all test methods that holds
 * multiple triple stores
 * <li>Create a new triple store per test method (lazily)
 * </ul>
 */
public class AbstractBlazegraphStorageTestCase {

    /**
     * Holds all the triples stores. Initialized once per test class.
     */
    private static TemporaryStore temporaryStore;
    /**
     * Triple store for the current test method. Lazily initialized.
     */
    private AbstractTripleStore store;

    /**
     * Get a TemporaryStore. Lazily initialized once per test class.
     */
    private static TemporaryStore temporaryStore() {
        if (temporaryStore != null) {
            return temporaryStore;
        }
        temporaryStore = new TemporaryStore();
        return temporaryStore;
    }

    /**
     * Get a triple store. Lazily initialized once per test method.
     */
    protected AbstractTripleStore store() {
        if (store != null) {
            return store;
        }
        Properties properties = initStoreProperties(new Properties());
        store = new TempTripleStore(temporaryStore(), properties, null);
        return store;
    }

    /**
     * Initialize store properties.
     * Allows property override by either updating properties object, or returning a new one.
     * @param properties Properties that have been set so far
     * @return Properties object after updating it
     */
    protected Properties initStoreProperties(Properties properties) {
        properties.setProperty("com.bigdata.rdf.store.AbstractTripleStore.vocabularyClass",
                WikibaseVocabulary.VOCABULARY_CLASS.getName());
        properties.setProperty("com.bigdata.rdf.store.AbstractTripleStore.inlineURIFactory",
                WikibaseInlineUriFactory.V002.class.getName());
        properties.setProperty("com.bigdata.rdf.store.AbstractTripleStore.extensionFactoryClass",
                WikibaseExtensionFactory.class.getName());
        properties.setProperty("com.bigdata.rdf.store.AbstractTripleStore.geoSpatial", "true");
        properties.setProperty("com.bigdata.rdf.store.AbstractTripleStore.geoSpatialIncludeBuiltinDatatypes",  "false");
        properties.setProperty("com.bigdata.rdf.store.AbstractTripleStore.geoSpatialDefaultDatatype", "http://www.opengis.net/ont/geosparql#wktLiteral");

        properties.setProperty("com.bigdata.rdf.store.AbstractTripleStore.geoSpatialDatatypeConfig.0",
                "{\"config\": "
                        + "{ \"uri\": \"" + GeoSparql.WKT_LITERAL + "\", "
                        + "\"literalSerializer\": \"" + WKTSerializer.class.getName() + "\",  "
                        + "\"fields\": [ "
                        + "{ \"valueType\": \"DOUBLE\", \"multiplier\": \"1000000000\", \"serviceMapping\": \"LONGITUDE\" }, "
                        + "{ \"valueType\": \"DOUBLE\", \"multiplier\": \"1000000000\", \"serviceMapping\": \"LATITUDE\" }, "
                        + "{ \"valueType\": \"LONG\", \"multiplier\":\"1\",\"minValue\":\"0\", \"serviceMapping\": \"COORD_SYSTEM\" } "
                        + "]}}");

        return properties;
    }

    /**
     * Close the temporary store used by this test.
     * @throws InterruptedException if the executor service fails to await termination
     */
    @AfterClass
    public static void closeTemporaryStore() throws InterruptedException {
        if (temporaryStore == null) {
            return;
        }
        ExecutorService executorService = temporaryStore.getExecutorService();
        temporaryStore.close();
        QueryEngine queryEngine = QueryEngineFactory.getInstance().getExistingQueryController(temporaryStore);
        if (queryEngine != null) {
            queryEngine.shutdownNow();
        }
        executorService.awaitTermination(20, TimeUnit.SECONDS);
        temporaryStore = null;
    }

    /**
     * Close the triple store used by the test that just finished.
     */
    @After
    public void closeStore() {
        if (store == null) {
            return;
        }
        store.close();
        store = null;
    }
}
