package ngsep.assembly;

import java.util.Collections;
import java.util.List;

import ngsep.sequences.FMIndex;

public class GraphBuilderFMIndex implements GraphBuilder {
	private final static int TALLY_DISTANCE = 100;
	private final static int SUFFIX_FRACTION = 25;

	@Override
	public SimplifiedAssemblyGraph buildAssemblyGraph(List<CharSequence> sequences) {
		System.out.println("	sorting sequences");
		long ini = System.currentTimeMillis();
		Collections.sort(sequences, (CharSequence l1, CharSequence l2) -> l2.length() - l1.length());
		System.out.println("	sort sequeces: " + (System.currentTimeMillis() - ini) / (double) 1000 + " s");

		System.out.println("	building FMIndexes");
		ini = System.currentTimeMillis();
		FMIndex index = new FMIndex();
		index.loadUnnamedSequences(sequences, TALLY_DISTANCE, SUFFIX_FRACTION);
		System.out.println("	build FMIndexes: " + (System.currentTimeMillis() - ini) / (double) 1000 + " s");

		System.out.println("	indentifing overlaps");
		GraphBuilderOverlapFinder overlapFinder = new GraphBuilderOverlapFinderQueue2();
		overlapFinder.calculate(sequences, index);
		System.out.println("	indentify overlaps: " + (System.currentTimeMillis() - ini) / (double) 1000 + " s");
		return overlapFinder.getGrap();
	}

	public static void main(String[] args) throws Exception {
		System.out.println("��� Esta funcion es solo para desarrollo !!!!");
		List<CharSequence> a = Assembler.load(args[0]);
		SimplifiedAssemblyGraph assemblyGraph = (new GraphBuilderFMIndex()).buildAssemblyGraph(a);
		assemblyGraph.save(args[1]);
	}

}
