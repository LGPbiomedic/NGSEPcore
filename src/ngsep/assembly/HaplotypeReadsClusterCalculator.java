/*******************************************************************************
 * NGSEP - Next Generation Sequencing Experience Platform
 * Copyright 2016 Jorge Duitama
 *
 * This file is part of NGSEP.
 *
 *     NGSEP is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     NGSEP is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with NGSEP.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package ngsep.assembly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import ngsep.alignments.ReadAlignment;
import ngsep.discovery.AlignmentsPileupGenerator;
import ngsep.discovery.PileupListener;
import ngsep.discovery.PileupRecord;
import ngsep.genome.GenomicRegion;
import ngsep.genome.GenomicRegionPositionComparator;
import ngsep.haplotyping.SingleIndividualHaplotyper;
import ngsep.math.NumberArrays;
import ngsep.sequences.DNASequence;
import ngsep.sequences.QualifiedSequence;
import ngsep.sequences.QualifiedSequenceList;
import ngsep.variants.CalledGenomicVariant;
import ngsep.variants.CalledSNV;
import ngsep.variants.SNV;

/**
 * 
 * @author Jorge Duitama
 *
 */
public class HaplotypeReadsClusterCalculator {

	private Logger log = Logger.getLogger(HaplotypeReadsClusterCalculator.class.getName());
	public static final int DEF_NUM_THREADS = 1;
	private static final int TIMEOUT_SECONDS = 30;
	
	private int numThreads = 1;
	public List<Set<Integer>> clusterReads(AssemblyGraph graph, int ploidy) {
		ThreadPoolExecutor poolClustering = new ThreadPoolExecutor(numThreads, numThreads, TIMEOUT_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		List<ClusterReadsTask> tasksList = new ArrayList<ClusterReadsTask>();
		List<List<AssemblyEdge>> paths = graph.getPaths(); 
		for(int i = 0; i < paths.size(); i++)
		{
			List<AssemblyEdge> path = paths.get(i);
			ClusterReadsTask task = new ClusterReadsTask(this, graph, path, i, ploidy);
			poolClustering.execute(task);
			tasksList.add(task);	
		}
		int finishTime = 10*graph.getNumSequences();
		poolClustering.shutdown();
		try {
			poolClustering.awaitTermination(finishTime, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
    	if(!poolClustering.isShutdown()) {
			throw new RuntimeException("The ThreadPoolExecutor was not shutdown after an await Termination call");
		}
    	List<Set<Integer>> pathClusters = new ArrayList<Set<Integer>>(ploidy);
    	//TODO: Algorithm for good merging of haplotype clusters
    	Set<Integer> hap0 = new HashSet<Integer>();
    	pathClusters.add(hap0);
    	Set<Integer> hap1 = new HashSet<Integer>();
    	pathClusters.add(hap1);
    	for(ClusterReadsTask task:tasksList) {
    		List<Set<Integer>> hapClusters = task.getClusters();
    		if(hapClusters.size()>=1) hap0.addAll(hapClusters.get(0));
    		if(hapClusters.size()>=2) hap1.addAll(hapClusters.get(1));
    	}
    	
    	return pathClusters;
	}
	
	List<Set<Integer>> clusterReadsPath(AssemblyGraph graph, List<AssemblyEdge> path, int pathIdx, int ploidy) {
		AssemblyPathReadsAligner aligner = new AssemblyPathReadsAligner();
		aligner.setLog(log);
		aligner.setAlignEmbedded(true);
		aligner.alignPathReads(graph, path, pathIdx);
		StringBuilder rawConsensus = aligner.getConsensus();
		List<ReadAlignment> alignments = aligner.getAlignedReads();
		if(alignments.size()==0) return new ArrayList<Set<Integer>>();
		String sequenceName = "diploidPath_"+pathIdx;
		for(ReadAlignment aln:alignments) aln.setSequenceName(sequenceName);
		Collections.sort(alignments, GenomicRegionPositionComparator.getInstance());
		List<CalledGenomicVariant> hetSNVs = findHeterozygousSNVs(rawConsensus, alignments, sequenceName);
		
		List<Set<Integer>> answer = new ArrayList<Set<Integer>>();
		List<List<ReadAlignment>> clusters = null;
		if(hetSNVs.size()>5) {
			SingleIndividualHaplotyper sih = new SingleIndividualHaplotyper();
			try {
				clusters = sih.phaseSequenceVariants(sequenceName, hetSNVs, alignments);
			} catch (IOException e) {
				throw new RuntimeException (e);
			}
		}
		if(clusters == null) {
			Set<Integer> sequenceIds = new HashSet<Integer>();
			for(ReadAlignment aln:alignments) sequenceIds.add(aln.getReadNumber());
			answer.add(sequenceIds);
			return answer;
		}
		for(List<ReadAlignment> cluster:clusters) {
			Set<Integer> sequenceIds = new HashSet<Integer>();
			for(ReadAlignment aln:cluster) sequenceIds.add(aln.getReadNumber());
			answer.add(sequenceIds);
		}
		return answer;
	}

	private List<CalledGenomicVariant> findHeterozygousSNVs(StringBuilder consensus, List<ReadAlignment> alignments, String sequenceName) {
		List<GenomicRegion> activeSegments = ConsensusBuilderBidirectionalWithPolishing.calculateActiveSegments(sequenceName, alignments);
		AlignmentsPileupGenerator generator = new AlignmentsPileupGenerator();
		generator.setLog(log);
		QualifiedSequenceList metadata = new QualifiedSequenceList();
		metadata.add(new QualifiedSequence(sequenceName,consensus.length()));
		generator.setSequencesMetadata(metadata);
		generator.setMaxAlnsPerStartPos(0);
		SimpleHeterozygousSNVsDetectorPileupListener hetSNVsListener = new SimpleHeterozygousSNVsDetectorPileupListener(consensus, activeSegments);
		generator.addListener(hetSNVsListener);
		
		int count = 0;
		for(ReadAlignment aln:alignments) {
			generator.processAlignment(aln);
			count++;
			if(count%1000==0) log.info("Sequence: "+sequenceName+". identified heterozygous SNVs from "+count+" alignments"); 
		}
		log.info("Sequence: "+sequenceName+". identified "+hetSNVsListener.getHeterozygousSNVs().size()+" heterozygous SNVs from "+count+" alignments");
		return hetSNVsListener.getHeterozygousSNVs();
	}

}
class ClusterReadsTask implements Runnable {
	private HaplotypeReadsClusterCalculator parent;
	private AssemblyGraph graph;
	private List<AssemblyEdge> path;
	private int pathIdx;
	private int ploidy;
	private List<Set<Integer>> clusters;
	public ClusterReadsTask(HaplotypeReadsClusterCalculator parent, AssemblyGraph graph, List<AssemblyEdge> path, int pathIdx, int ploidy) {
		super();
		this.parent = parent;
		this.graph = graph;
		this.path = path;
		this.pathIdx = pathIdx;
		this.ploidy = ploidy;
	}
	@Override
	public void run() {
		clusters = parent.clusterReadsPath(graph,path, pathIdx, ploidy);
	}
	public List<Set<Integer>> getClusters() {
		return clusters;
	}
}
class SimpleHeterozygousSNVsDetectorPileupListener implements PileupListener {

	private StringBuilder consensus;
	private List<GenomicRegion> indelRegions;
	private List<CalledGenomicVariant> heterozygousSNVs = new ArrayList<CalledGenomicVariant>();
	private int nextIndelPos = 0;

	public SimpleHeterozygousSNVsDetectorPileupListener(StringBuilder consensus, List<GenomicRegion> indelRegions) {
		super();
		this.consensus = consensus;
		this.indelRegions = indelRegions;
	}
	
	public List<CalledGenomicVariant> getHeterozygousSNVs() {
		return heterozygousSNVs;
	}
	@Override
	public void onPileup(PileupRecord pileup) {
		int pos = pileup.getPosition();
		//Check if pileup is located within an indel region
		while(nextIndelPos<indelRegions.size()) {
			GenomicRegion region = indelRegions.get(nextIndelPos);
			if(region.getFirst()<=pos && pos<=region.getLast()) return;
			else if (pos<region.getFirst()) break;
			nextIndelPos++;
		}
		List<ReadAlignment> alns = pileup.getAlignments();
		//Index alignments per nucleotide call
		int n = DNASequence.BASES_STRING.length();
		Map<Character,List<ReadAlignment>> alnsPerNucleotide = new HashMap<Character, List<ReadAlignment>>(n);
		for(int i=0;i<n;i++) {
			alnsPerNucleotide.put(DNASequence.BASES_STRING.charAt(i), new ArrayList<ReadAlignment>(alns.size()));
		}
		for(ReadAlignment aln:alns) {
			CharSequence call = aln.getAlleleCall(pos);
			if(call == null) continue;
			char c = call.charAt(0);
			List<ReadAlignment> alnsAllele = alnsPerNucleotide.get(c);
			if(alnsAllele==null) continue;
			alnsAllele.add(aln);
		}
		//Extract counts from map of allele calls
		int [] acgtCounts = new int [n];
		for(int i=0;i<n;i++) {
			char c = DNASequence.BASES_STRING.charAt(i);
			acgtCounts[i] = alnsPerNucleotide.get(c).size();
		}
		int maxIdx = NumberArrays.getIndexMaximum(acgtCounts);
		int secondMaxIdx = NumberArrays.getIndexMaximum(acgtCounts, maxIdx);
		int maxCount = acgtCounts[maxIdx];
		int secondCount = acgtCounts[secondMaxIdx];
		char maxBp = DNASequence.BASES_STRING.charAt(maxIdx);
		char secondBp = DNASequence.BASES_STRING.charAt(secondMaxIdx);
		char refBase = consensus.charAt(pileup.getPosition()-1);
		char altBase = (maxBp==refBase)?secondBp:maxBp;
		if(maxCount+secondCount>=alns.size()-1 && secondCount>=5 && (refBase==maxBp || refBase == secondBp)) {
			heterozygousSNVs.add(new CalledSNV(new SNV(pileup.getSequenceName(), pileup.getPosition(), refBase, altBase), CalledGenomicVariant.GENOTYPE_HETERO));
		}
	}

	@Override
	public void onSequenceStart(QualifiedSequence sequence) {
	}

	@Override
	public void onSequenceEnd(QualifiedSequence sequence) {
	}
	
}
