package org.wikidata.query.rdf.updater.consumer;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.concurrent.NotThreadSafe;

import org.openrdf.model.Statement;
import org.wikidata.query.rdf.tool.rdf.ConsumerPatch;
import org.wikidata.query.rdf.updater.DiffEventData;
import org.wikidata.query.rdf.updater.MutationEventData;
import org.wikidata.query.rdf.updater.RDFChunkDeserializer;

import com.google.common.collect.Sets;

import lombok.Getter;

@Getter
@NotThreadSafe
public class PatchAccumulator {
    private final Set<Statement> allAdded = new HashSet<>();
    private final Set<Statement> allRemoved = new HashSet<>();
    private final Set<Statement> allLinkedSharedElts = new HashSet<>();
    private final Set<Statement> allUnlinkedSharedElts = new HashSet<>();
    private final Set<String> allEntitiesToDelete = new HashSet<>();
    private int totalAccumulated;
    private final RDFChunkDeserializer deser;

    public PatchAccumulator(RDFChunkDeserializer deser) {
        this.deser = deser;
    }

    public int size() {
        return allAdded.size() + allRemoved.size() + allLinkedSharedElts.size() + allUnlinkedSharedElts.size();
    }

    public int accumulate(Collection<Statement> added, Collection<Statement> removed,
                          Collection<Statement> linkedSharedElts, Collection<Statement> unlinkedSharedElts) {
        totalAccumulated += added.size() + removed.size() + linkedSharedElts.size() + unlinkedSharedElts.size();
        Set<Statement> newlyAdded = new HashSet<>(added);
        Set<Statement> newlyRemoved = new HashSet<>(removed);
        Set<Statement> newlyLinkedSharedElts = new HashSet<>(linkedSharedElts);
        Set<Statement> newlyUnlinkedSharedElts = new HashSet<>(unlinkedSharedElts);
        removeIntersection(allAdded, newlyRemoved);
        removeIntersection(newlyAdded, allRemoved);
        removeIntersection(allLinkedSharedElts, newlyUnlinkedSharedElts);
        removeIntersection(allUnlinkedSharedElts, newlyLinkedSharedElts);
        allAdded.addAll(newlyAdded);
        allRemoved.addAll(newlyRemoved);
        allLinkedSharedElts.addAll(newlyLinkedSharedElts);
        allUnlinkedSharedElts.addAll(newlyUnlinkedSharedElts);
        return size();
    }

    private void removeIntersection(Set<Statement> set1, Set<Statement> set2) {
        Set<Statement> intersection = new HashSet<>(Sets.intersection(set1, set2));
        set1.removeAll(intersection);
        set2.removeAll(intersection);
    }

    public ConsumerPatch asPatch() {
        return new ConsumerPatch(unmodifiableList(new ArrayList<>(allAdded)),
                unmodifiableList(new ArrayList<>(allLinkedSharedElts)),
                unmodifiableList(new ArrayList<>(allRemoved)),
                unmodifiableList(new ArrayList<>(allUnlinkedSharedElts)),
                unmodifiableList(new ArrayList<>(allEntitiesToDelete)));
    }

    public void accumulate(MutationEventData value) {
        if (value instanceof DiffEventData) {
            DiffEventData val = (DiffEventData) value;
            accumulate(val.getRdfAddedData() != null ? deser.deser(val.getRdfAddedData(), "unused") : emptyList(),
                    val.getRdfDeletedData() != null ? deser.deser(val.getRdfDeletedData(), "unused") : emptyList(),
                    val.getRdfLinkedSharedData() != null ? deser.deser(val.getRdfLinkedSharedData(), "unused") : emptyList(),
                    val.getRdfUnlinkedSharedData() != null ? deser.deser(val.getRdfUnlinkedSharedData(), "unused") : emptyList());
        }
    }

    public void storeEntityIdsToDelete(String entity) {
        allEntitiesToDelete.add(entity);
    }
}
