package ngsep.assembly;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;

import ngsep.alignments.ReadAlignment;
import ngsep.sequences.FMIndex;

import static ngsep.assembly.TimeUtilities.progress;
import static ngsep.assembly.TimeUtilities.timeGroup;
import static ngsep.assembly.TimeUtilities.timeIt;

public class GraphBuilderFMIndex implements GraphBuilder {
	private final static int TALLY_DISTANCE = 100;
	private final static int SUFFIX_FRACTION = 25;

	private FMIndex index;
	private KmerIterator kmerIterator;
	private List<CharSequence> sequences;
	private AssemblyConfiguration config;

	private SimplifiedAssemblyGraph assemblyGraph;
	private Map<Integer, List<int[]>> hits;

	@Override
	public SimplifiedAssemblyGraph buildAssemblyGraph(List<CharSequence> sequences, AssemblyConfiguration config) {
		this.sequences = sequences;
		this.config = config;
		timeIt("    Sort sequences", () -> {
			// Collections.sort(sequences, (l1, l2) -> l2.length() - l1.length())
			TestSort(sequences);
		});

		index = timeGroup("    Build FMindex", () -> {
			FMIndex ans = new FMIndex();
			ans.loadUnnamedSequences(sequences, TALLY_DISTANCE, SUFFIX_FRACTION);
			return ans;
		});

		timeGroup("    Indentify overlaps", () -> {
			kmerIterator = new KmerIterator(config);
			assemblyGraph = new SimplifiedAssemblyGraph(sequences);
			hits = new HashMap<Integer, List<int[]>>(sequences.size());

			printRates();
			timeIt("      Find overlaps ", () -> findOverlapsAndEmbedded());
			timeIt("      Clean Graph", () -> assemblyGraph.removeAllEmbeddedsIntoGraph());
		});
		return assemblyGraph;
	}

	public void printRates() {
		System.out.println("      --------------------------------");
		System.out.println("      SEARCH_KMER_LENGTH: " + config.overlap().getKmerLength());
		System.out.println("      SEARCH_KMER_DISTANCE: " + config.overlap().getKmerDistance());
		System.out.println("      MAX_KMER_DES: " + config.overlap().getMaxKmerDiff());
		System.out.println("      KMER_COVERAGE: " + config.overlap().getRate_of_cover());
		System.out.println("      MIN_COVER_RATE: " + config.overlap().getMinKmerCoverRate());
		System.out.println("      --------------------------------");
	}

	public void findOverlapsAndEmbedded() {
		HitsAligner aligner = new TreesHitAligner(assemblyGraph, config, sequences);
		for (int seqId = 0, excp = 0; seqId < sequences.size(); seqId++) {
			progress("      Find overlaps ", seqId + assemblyGraph.amuontOfEmbeddedSequences() - excp,
					sequences.size());
			if (assemblyGraph.isEmbedded(seqId)) {
				excp++;
				continue;
			}

			calculateHits(seqId, kmerIterator.positiveStrand(sequences.get(seqId)));
			for (Entry<Integer, List<int[]>> entry : hits.entrySet())
				if (hits.size() > 1)
					aligner.Aling(seqId, entry.getKey(), false, entry.getValue());

			calculateHits(seqId, kmerIterator.negativeStrand(sequences.get(seqId)));
			for (Entry<Integer, List<int[]>> entry : hits.entrySet())
				if (hits.size() > 1)
					aligner.Aling(seqId, entry.getKey(), true, entry.getValue());
		}
	}

	private void calculateHits(int id_Ref, Iterable<Entry<Integer, String>> kmerIters) {
		hits.clear();
		for (Entry<Integer, String> entry : kmerIters) {
			int pos_Ref = entry.getKey();
			for (ReadAlignment readAlignment : index.search(entry.getValue())) {
				int id_Lec = Integer.parseInt(readAlignment.getSequenceName());
				int pos_Lec = readAlignment.getFirst();

				if (id_Ref < id_Lec && !assemblyGraph.isEmbedded(id_Lec))
					hits.computeIfAbsent(id_Lec, x -> new LinkedList<int[]>()).add(new int[] { pos_Ref, pos_Lec });
			}
		}
	}

	@SuppressWarnings("unused")
	/**
	 * method necessary to generate the files to compare with graphComparator
	 * 
	 * @param sequences
	 */
	private static void TestSort(List<CharSequence> sequences) {
		PriorityQueue<Entry<Integer, Integer>> heap = new PriorityQueue<>(
				(Entry<Integer, Integer> l1, Entry<Integer, Integer> l2) -> l2.getKey() - l1.getKey());
		for (int i = 0; i < sequences.size(); i++)
			heap.add(new SimpleEntry<>(sequences.get(i).length(), i));
		try (PrintStream pr = new PrintStream(new FileOutputStream("ind"))) {
			List<CharSequence> sorted = new ArrayList<>(sequences.size());
			while (!heap.isEmpty()) {
				Entry<Integer, Integer> an = heap.poll();
				sorted.add(sequences.get(an.getValue()));
				pr.println(an.getValue());
			}
			sequences.clear();
			sequences.addAll(sorted);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

}
