package org.wikidata.query.rdf.tool;

import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;
import static org.wikidata.query.rdf.test.Matchers.subjectPredicateObjectMatchers;
import static org.wikidata.query.rdf.test.StatementHelper.randomStatementsAbout;
import static org.wikidata.query.rdf.tool.TupleQueryResultHelper.toIterable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.openrdf.model.Statement;
import org.openrdf.query.BindingSet;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.TupleQueryResult;
import org.wikidata.query.rdf.test.Randomizer;
import org.wikidata.query.rdf.tool.exception.ContainedException;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Does lots of simultaneous IO and state mutation on multiple namespaces.
 */
@Ignore("temporarily disable")
public class IOBlastingIntegrationTest {

    @ClassRule
    public static final Randomizer RANDOMIZER = new Randomizer();

    // works up to at least 10,000,000, albeit slowly (3min)
    private static final int MAX_STATEMENTS_PER_NAMESPACE = 100;
    private static final int TOTAL_NAMESPACES = 20;

    private final ExecutorService pool = Executors.newFixedThreadPool(
            TOTAL_NAMESPACES,
            new ThreadFactoryBuilder().setNameFormat("IO-Blasting-%d").build());
    private List<Future<IOBlasterResults>> resultses = new ArrayList<>();

    @Before
    public void setup() {
        for (int i = 1; i <= TOTAL_NAMESPACES; i++) {
            resultses.add(pool.submit(new IOBlaster("wdq" + i)));
        }
    }

    @After
    public void teardown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
    }

    @Test
    public void blast() throws Exception {
        for (Future<IOBlasterResults> future : resultses) {
            IOBlasterResults result = future.get();
            assertThat(result.results(), hasItems(result.matchers()));
        }
    }

    /**
     * Java needs tuples.
     */
    private static final class IOBlasterResults {

        private final Iterable<BindingSet> results;

        private final Matcher<BindingSet>[] matchers;

        IOBlasterResults(Iterable<BindingSet> first, Matcher<BindingSet>[] second) {
            results = first;
            matchers = second;
        }

        Iterable<BindingSet> results() {
            return results;
        }

        Matcher<BindingSet>[] matchers() {
            return matchers;
        }

    }

    /**
     * An asynchronously-callable utility to blast a triple store with oodles of
     * inserts, then a big select.
     */
    private static final class IOBlaster implements Callable<IOBlasterResults> {

        private final RdfRepositoryForTesting rdfRepository;

        IOBlaster(String namespace) {
            rdfRepository = new RdfRepositoryForTesting(namespace);
        }

        /**
         * Generate a whole mess of statements, and sync them into the triple
         * store.
         */
        private static List<Statement> generateAndInsert(RdfRepositoryForTesting rdfRepository) {
            String s = "Q" + RANDOMIZER.randomIntBetween(1, 65536);
            int statementCount = RANDOMIZER.randomIntBetween(1, MAX_STATEMENTS_PER_NAMESPACE);

            // Make some noise
            rdfRepository.sync(s, randomStatementsAbout(RANDOMIZER, s, statementCount), null);
            rdfRepository.sync(s, randomStatementsAbout(RANDOMIZER, s, statementCount), null);

            // Now the *real* statements
            List<Statement> statements = randomStatementsAbout(RANDOMIZER, s, statementCount);
            rdfRepository.sync(s, statements, null);

            return statements;
        }

        /**
         * Query for everything in the triple store, and set up matchers for the
         * expected statements.
         */
        private IOBlasterResults queryForAllMatches(List<Statement> statements) throws QueryEvaluationException {
            TupleQueryResult tupleQueryResult = rdfRepository.query("SELECT * WHERE {?s ?p ?o}");
            Iterable<BindingSet> results = toIterable(tupleQueryResult);
            Matcher<BindingSet>[] matchers = subjectPredicateObjectMatchers(statements);
            return new IOBlasterResults(results, matchers);
        }

        @Override
        public IOBlasterResults call() throws Exception {
            try {
                // It's possible the namespace stuck around because of earlier
                // errors or cancellations, so try to clean it up so we're good
                // to go for next time.
                rdfRepository.deleteNamespace();
            } catch (ContainedException e) {
                // Don't really care if something went wrong, e.g. the namespace
                // doesn't yet exist.
            }
            rdfRepository.createNamespace();
            List<Statement> statements = generateAndInsert(rdfRepository);
            IOBlasterResults results = queryForAllMatches(statements);
            return results;
        }
    }

}
