package ngsep.assembly;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ngsep.alignments.MinimizersTableReadAlignmentAlgorithm;
import ngsep.sequences.UngappedSearchHit;
import ngsep.sequences.KmerHitsCluster;

public class KmerHitsAssemblyEdgesFinder {

	private AssemblyGraph graph;
	
	public static final int DEF_MIN_KMER_PCT = 5;
	
	private int minKmerPercentage=DEF_MIN_KMER_PCT;
	
	private double minProportionOverlap = 0.05;
	
	private double minProportionEvidence = 0;
	
	private int idxDebug = -1;
	
	
	
	public KmerHitsAssemblyEdgesFinder(AssemblyGraph graph) {
		this.graph = graph;
	}
	
	public AssemblyGraph getGraph() {
		return graph;
	}
	
	public int getMinKmerPercentage() {
		return minKmerPercentage;
	}

	public void setMinKmerPercentage(int minKmerPercentage) {
		this.minKmerPercentage = minKmerPercentage;
	}

	public double getMinProportionOverlap() {
		return minProportionOverlap;
	}

	public void setMinProportionOverlap(double minProportionOverlap) {
		this.minProportionOverlap = minProportionOverlap;
	}

	public void updateGraphWithKmerHitsMap(int queryIdx, CharSequence query, boolean queryRC, int selfHitsCount, Map<Integer, List<UngappedSearchHit>> hitsBySubjectIdx) {
		Set<Integer> subjectIdxs = new HashSet<Integer>();
		for(int subjectIdx:hitsBySubjectIdx.keySet()) {
			int subjectCount = hitsBySubjectIdx.get(subjectIdx).size();
			if(subjectIdx< queryIdx) {
				if (queryIdx == idxDebug && subjectCount>10) System.out.println("EdgesFinder. Query: "+queryIdx+" "+queryRC+" Subject sequence: "+subjectIdx+" hits: "+subjectCount+" self hits: "+selfHitsCount);
				subjectIdxs.add(subjectIdx);
			}
		}
	
		//Combined query min coverage and percentage of kmers
		int minCount = (int) (minProportionOverlap*minKmerPercentage*selfHitsCount/100);
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" "+queryRC+" Subject sequences: "+subjectIdxs.size());
		for(int subjectIdx:subjectIdxs) {
			int subjectLength = graph.getSequenceLength(subjectIdx);
			List<UngappedSearchHit> hits = hitsBySubjectIdx.get(subjectIdx);
			if(hits.size()<minCount) continue;
			List<KmerHitsCluster> subjectClusters = KmerHitsCluster.clusterRegionKmerAlns(query.length(), subjectLength, hits, 0);
			if(subjectClusters.size()==0) continue;
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" "+queryRC+" Subject idx: "+subjectIdx+" hits: "+hits.size()+" clusters: "+subjectClusters.size());
			Collections.sort(subjectClusters, (o1,o2)-> o2.getNumDifferentKmers()-o1.getNumDifferentKmers());
			KmerHitsCluster subjectCluster = subjectClusters.get(0);
			subjectCluster.setSelfHitsCountQuery(selfHitsCount);
			updateGraphWithKmerCluster(queryIdx, query, queryRC, subjectClusters.get(0));
		}
	}
	private void updateGraphWithKmerCluster(int querySequenceId, CharSequence query,  boolean queryRC, KmerHitsCluster cluster) {
		//Process cluster
		cluster.summarize();
		if(passFilters(querySequenceId, query.length(), cluster)) {
			processCluster(querySequenceId, query, queryRC, cluster);
		}
		cluster.disposeHits();
	}
	
	private boolean passFilters (int querySequenceId, int queryLength, KmerHitsCluster cluster) {
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		double overlap = cluster.getPredictedOverlap();
		double overlapSelfCount = overlap*cluster.getSelfHitsCountQuery()/queryLength;
		double pct = 100.0*cluster.getNumDifferentKmers()/overlapSelfCount;
		int queryEvidenceLength = cluster.getQueryEvidenceEnd()-cluster.getQueryEvidenceStart();
		int subjectEvidenceLength = cluster.getSubjectEvidenceEnd() - cluster.getSubjectEvidenceStart();
		//if(querySequenceId==idxDebug) System.out.println("EdgesFinder. Evaluating cluster. qlen "+queryLength+" QPred: "+cluster.getQueryPredictedStart()+" - "+cluster.getQueryPredictedEnd()+" QEv: "+cluster.getQueryEvidenceStart()+" - "+cluster.getQueryEvidenceEnd()+" subject len: "+subjectLength+" Subject: "+cluster.getSequenceIdx()+" sPred: "+cluster.getSubjectPredictedStart()+" - "+cluster.getSubjectPredictedEnd()+" sEv: "+cluster.getSubjectEvidenceStart()+" - "+cluster.getSubjectEvidenceEnd()+" overlap1 "+overlap+" overlap2: "+cluster.getPredictedOverlap() +" plain count: "+cluster.getNumDifferentKmers()+" weighted count: "+cluster.getWeightedCount()+" pct: "+pct);
		if(overlap < minProportionOverlap*queryLength) return false;
		if(overlap < minProportionOverlap*subjectLength) return false;
		if(queryEvidenceLength < minProportionEvidence*overlap) return false;
		if(subjectEvidenceLength < minProportionEvidence*overlap) return false;
		if(pct<minKmerPercentage) return false;
		return true;
	}

	public void printSubjectHits(List<UngappedSearchHit> subjectHits) {
		for(UngappedSearchHit hit:subjectHits) {
			System.out.println(hit.getQueryIdx()+" "+hit.getSequenceIdx()+":"+hit.getStart());
		}
		
	}

	private void processCluster(int querySequenceId, CharSequence query, boolean queryRC, KmerHitsCluster cluster) {
		int queryLength = query.length();
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		//Zero based limits
		int startSubject = cluster.getSubjectPredictedStart();
		int endSubject = cluster.getSubjectPredictedEnd();
		if(querySequenceId==idxDebug) System.out.println("Processing cluster. Query: "+querySequenceId+" length: "+queryLength+ " subject: "+cluster.getSubjectIdx()+" length: "+subjectLength);
		if(startSubject>=0 && endSubject<=subjectLength) {
			addEmbedded(querySequenceId, query, queryRC, cluster);
		} else if (startSubject>=0) {
			addQueryAfterSubjectEdge(querySequenceId, query, queryRC, cluster);
		} else if (endSubject<=subjectLength) {
			addQueryBeforeSubjectEdge(querySequenceId, query, queryRC, cluster);
		} else {
			// Similar sequences. Add possible embedded
			addEmbedded(querySequenceId, query, queryRC, cluster);
		}
	}
	private void addEmbedded(int querySequenceId, CharSequence query, boolean queryRC, KmerHitsCluster cluster) {
		int startSubject = cluster.getSubjectPredictedStart();
		int endSubject = cluster.getSubjectPredictedEnd();
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		AssemblyEmbedded embeddedEvent = new AssemblyEmbedded(querySequenceId, graph.getSequence(querySequenceId), queryRC, subjectSeqIdx, startSubject, endSubject);
		embeddedEvent.setHostEvidenceStart(cluster.getSubjectEvidenceStart());
		embeddedEvent.setHostEvidenceEnd(cluster.getSubjectEvidenceEnd());
		embeddedEvent.setNumSharedKmers(cluster.getNumDifferentKmers());
		int [] alnData = MinimizersTableReadAlignmentAlgorithm.simulateAlignment(subjectSeqIdx, subjectLength, query.length(), cluster);
		embeddedEvent.setCoverageSharedKmers(alnData[0]);
		embeddedEvent.setWeightedCoverageSharedKmers(alnData[1]);
		embeddedEvent.setMismatches(alnData[2]);
		synchronized (graph) {
			graph.addEmbedded(embeddedEvent);
		}
		if (querySequenceId==idxDebug) System.out.println("Query: "+querySequenceId+" embedded in "+subjectSeqIdx);
	}
	private void addQueryAfterSubjectEdge(int querySequenceId, CharSequence query, boolean queryRC, KmerHitsCluster cluster) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		AssemblyVertex vertexSubject = graph.getVertex(subjectSeqIdx, false);
		AssemblyVertex vertexQuery = graph.getVertex(querySequenceId, !queryRC);
		int overlap = cluster.getPredictedOverlap();
		AssemblyEdge edge = new AssemblyEdge(vertexSubject, vertexQuery, overlap);
		//ReadAlignment aln = aligner.buildCompleteAlignment(subjectSeqIdx, graph.getSequence(subjectSeqIdx).getCharacters(), query, cluster);
		//int mismatches = overlap;
		//if(aln!=null) mismatches = aln.getNumMismatches();
		int [] alnData = MinimizersTableReadAlignmentAlgorithm.simulateAlignment(subjectSeqIdx, subjectLength, queryLength, cluster);
		edge.setCoverageSharedKmers(alnData[0]);
		edge.setWeightedCoverageSharedKmers(alnData[1]);
		edge.setMismatches(alnData[2]);
		edge.setNumSharedKmers(cluster.getNumDifferentKmers());
		edge.setOverlapStandardDeviation(cluster.getPredictedOverlapSD());
		synchronized (graph) {
			graph.addEdge(edge);
		}
		if(querySequenceId==idxDebug) System.out.println("Edge between subject: "+vertexSubject.getUniqueNumber()+" and query "+vertexQuery.getUniqueNumber()+" overlap: "+overlap+" mismatches: "+edge.getMismatches()+" cost: "+edge.getCost());
	}
	private void addQueryBeforeSubjectEdge(int querySequenceId, CharSequence query, boolean queryRC, KmerHitsCluster cluster) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		AssemblyVertex vertexSubject = graph.getVertex(subjectSeqIdx, true);
		AssemblyVertex vertexQuery = graph.getVertex(querySequenceId, queryRC);
		int overlap = cluster.getPredictedOverlap();
		AssemblyEdge edge = new AssemblyEdge(vertexQuery, vertexSubject, overlap);
		//ReadAlignment aln = aligner.buildCompleteAlignment(subjectSeqIdx, graph.getSequence(subjectSeqIdx).getCharacters(), query, cluster);
		//int mismatches = overlap;
		//if(aln!=null) mismatches = aln.getNumMismatches();
		int [] alnData = MinimizersTableReadAlignmentAlgorithm.simulateAlignment(subjectSeqIdx, subjectLength, queryLength, cluster);
		edge.setCoverageSharedKmers(alnData[0]);
		edge.setWeightedCoverageSharedKmers(alnData[1]);
		edge.setMismatches(alnData[2]);
		edge.setNumSharedKmers(cluster.getNumDifferentKmers());
		edge.setOverlapStandardDeviation(cluster.getPredictedOverlapSD());
		synchronized (graph) {
			graph.addEdge(edge);
		}
		if(querySequenceId==idxDebug) System.out.println("Edge between query: "+vertexQuery.getUniqueNumber()+" and subject "+vertexSubject.getUniqueNumber()+" overlap: "+overlap+" mismatches: "+edge.getMismatches()+" cost: "+edge.getCost());
	}
}
