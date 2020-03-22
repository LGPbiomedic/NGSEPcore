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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import ngsep.alignments.LongReadsAligner;
import ngsep.alignments.ReadAlignment;
import ngsep.discovery.AlignmentsPileupGenerator;
import ngsep.discovery.IndelRealignerPileupListener;
import ngsep.discovery.VariantPileupListener;
import ngsep.genome.GenomicRegionPositionComparator;
import ngsep.genome.ReferenceGenome;
import ngsep.sequences.DNAMaskedSequence;
import ngsep.sequences.DNASequence;
import ngsep.sequences.QualifiedSequence;
import ngsep.sequences.RawRead;
import ngsep.variants.CalledGenomicVariant;

/**
 * 
 * @author Jorge Duitama
 *
 */
public class ConsensusBuilderBidirectionalWithPolishing implements ConsensusBuilder {
	
	private Logger log = Logger.getLogger(ConsensusBuilderBidirectionalWithPolishing.class.getName());
	private static final String MOCK_REFERENCE_NAME = "Consensus";
	
	private LongReadsAligner aligner = new LongReadsAligner();
	
	public Logger getLog() {
		return log;
	}

	public void setLog(Logger log) {
		this.log = log;
	}

	@Override
	public List<CharSequence> makeConsensus(AssemblyGraph graph) 
	{
		//List of final contigs
		List<CharSequence> consensusList = new ArrayList<CharSequence>();
		List<List<AssemblyEdge>> paths = graph.getPaths(); 
		for(int i = 0; i < paths.size(); i++)
		{
			List<AssemblyEdge> path = paths.get(i);
			CharSequence consensusPath = makeConsensus (graph, path);
			consensusList.add(consensusPath);
		}
		
		return consensusList;
	}
	
	private CharSequence makeConsensus(AssemblyGraph graph, List<AssemblyEdge> path) {
		StringBuilder rawConsensus = new StringBuilder();
		AssemblyVertex lastVertex = null;
		List<ReadAlignment> alignments = new ArrayList<ReadAlignment>();
		int totalReads = 0;
		int unalignedReads = 0;
		String pathS = "";
		if(path.size()==1) {
			rawConsensus.append(path.get(0).getVertex1().getRead());
			return rawConsensus;
		}
		for(int j = 0; j < path.size(); j++) {
			//Needed to find which is the origin vertex
			AssemblyEdge edge = path.get(j);
			AssemblyVertex vertexPreviousEdge;
			AssemblyVertex vertexNextEdge;
			//If the first edge is being checked, compare to the second edge to find the origin vertex
			if(j == 0) {
				AssemblyEdge nextEdge = path.get(j + 1);
				vertexNextEdge = edge.getSharedVertex(nextEdge);
				if(vertexNextEdge== null) throw new RuntimeException("Inconsistency found in first edge of path");
				vertexPreviousEdge = edge.getVertex1();
				if(vertexPreviousEdge == vertexNextEdge) vertexPreviousEdge = edge.getVertex2();
			}
			else if (lastVertex == edge.getVertex1()) {
				vertexPreviousEdge = edge.getVertex1();
				vertexNextEdge = edge.getVertex2();
			}
			else if (lastVertex == edge.getVertex2()) {
				vertexPreviousEdge = edge.getVertex2();
				vertexNextEdge = edge.getVertex1();
			}
			else {
				throw new RuntimeException("Inconsistency found in path");
			}
			if(j == 0) {
				pathS = pathS.concat(vertexPreviousEdge.getSequenceIndex() + ",");
				CharSequence seq = vertexPreviousEdge.getRead();
				boolean reverse = !vertexPreviousEdge.isStart();
				if(reverse) seq = DNAMaskedSequence.getReverseComplement(seq);
				rawConsensus.append(seq);
			} 
			else if(vertexPreviousEdge.getRead()!=vertexNextEdge.getRead()) {
				// Augment consensus with the next path read
				CharSequence nextPathSequence = vertexNextEdge.getRead();
				boolean reverse = !vertexNextEdge.isStart();
				if(reverse) nextPathSequence = DNAMaskedSequence.getReverseComplement(nextPathSequence);
				
				
				int overlap = edge.getOverlap();
				//TODO: see if it is worth to improve the estimated overlap
				/*ReadAlignment aln = aligner.alignRead(rawConsensus, nextPathSequence, Math.max(0, rawConsensus.length()-overlap-10), rawConsensus.length(), MOCK_REFERENCE_NAME);
				if(aln== null) {
					log.info("Path sequence with id "+vertexNextEdge.getSequenceIndex()+" and length "+rawConsensus.length()+" could not be aligned to consensus");
				} else {
					int newOverlap = aln.getReadPosition(rawConsensus.length()-1);
					if(newOverlap >0 && Math.abs(newOverlap-overlap)<20 ) {
						overlap = newOverlap;
					} else if (newOverlap>0) {
						log.info("Aligning "+vertexNextEdge.getSequenceIndex()+" to consensus end. Consensus length:"+rawConsensus.length()+" Overlap from kmers "+overlap+". Overlap from alignment "+newOverlap);
					}
					//System.out.println("Next path read id: "+vertexNextEdge.getSequenceIndex()+" Remainder calculated from alignment: "+remainder+" remainder from edge: "+(seq.length()-overlap)+" overlap: "+overlap+" length: "+seq.length());
					//if(newStart > 0 && newStart>lastIntegratedReadStart-200 && newStart<lastIntegratedReadStart ) lastIntegratedReadStart = newStart;
				}*/
				
				if(overlap<nextPathSequence.length()) {
					pathS = pathS.concat(vertexNextEdge.getSequenceIndex() + ",");
					String remainingSegment = nextPathSequence.subSequence(overlap, nextPathSequence.length()).toString();
					rawConsensus.append(remainingSegment.toUpperCase());
				} else {
					log.warning("Non embedded edge has overlap: "+overlap+ " and length: "+nextPathSequence.length());
				}
				
			}
			if(vertexPreviousEdge.getRead()==vertexNextEdge.getRead()) {
				//Align to consensus next path read and its embedded sequences
				CharSequence read = vertexPreviousEdge.getRead();
				boolean reverse = !vertexPreviousEdge.isStart();
				if(reverse) read = DNASequence.getReverseComplement(read.toString());
				Map<CharSequence, Integer> uniqueKmersSubject = aligner.extractUniqueKmers(rawConsensus,rawConsensus.length()-read.length(),rawConsensus.length());
				totalReads++;
				ReadAlignment alnRead = aligner.alignRead(rawConsensus, read, uniqueKmersSubject, MOCK_REFERENCE_NAME, 0.5);
				if (alnRead!=null) {
					alnRead.setQualityScores(RawRead.generateFixedQSString('5', read.length()));
					alignments.add(alnRead);
				}
				else unalignedReads++;
				if (totalReads%100==0) log.info("Aligning. Processed reads: "+totalReads+" alignments: "+alignments.size()+" unaligned: "+unalignedReads);
				
				List<AssemblyEmbedded> embeddedList = graph.getAllEmbedded(vertexPreviousEdge.getSequenceIndex());
				//List<AssemblyEmbedded> embeddedList = graph.getEmbeddedByHostId(vertexPreviousEdge.getSequenceIndex());
				for(AssemblyEmbedded embedded:embeddedList) {
					CharSequence embeddedRead = embedded.getRead();
					boolean reverseE = (reverse!=embedded.isReverse());
					if(reverseE) embeddedRead = DNASequence.getReverseComplement(embeddedRead.toString());
					totalReads++;
					ReadAlignment alnEmbedded = aligner.alignRead(rawConsensus, embeddedRead, uniqueKmersSubject, MOCK_REFERENCE_NAME, 0.5);
					if(alnEmbedded!=null) {
						alnEmbedded.setQualityScores(RawRead.generateFixedQSString('5', read.length()));
						alignments.add(alnEmbedded);
					}
					else unalignedReads++;
					if (totalReads%100==0) log.info("Aligning. Processed reads: "+totalReads+" alignments: "+alignments.size()+" unaligned: "+unalignedReads);
				}
			}
			lastVertex = vertexNextEdge;
		}
		log.info("Total reads: "+totalReads+" alignments: "+alignments.size()+" unaligned: "+unalignedReads);
		log.info("Path: "+pathS);
		String consensus = rawConsensus.toString();
		//return consensus;
		List<CalledGenomicVariant> variants = callVariants(consensus,alignments);
		log.info("Identified "+variants.size()+" total variants from read alignments");
		return applyVariants(consensus, variants);
	}


	private List<CalledGenomicVariant> callVariants(String consensus, List<ReadAlignment> alignments) {
		AlignmentsPileupGenerator generator = new AlignmentsPileupGenerator();
		
		ReferenceGenome genome = new ReferenceGenome(new QualifiedSequence(MOCK_REFERENCE_NAME, consensus));
		generator.setSequencesMetadata(genome.getSequencesMetadata());
		generator.setMaxAlnsPerStartPos(100);
		generator.setMinMQ(80);
		IndelRealignerPileupListener realignerListener = new IndelRealignerPileupListener();
		realignerListener.setGenome(genome);
		generator.addListener(realignerListener);
		VariantPileupListener varListener = new VariantPileupListener();
		varListener.setMinQuality((short) 30);
		varListener.setGenome(genome);
		generator.addListener(varListener);
		Collections.sort(alignments, GenomicRegionPositionComparator.getInstance());
		for(ReadAlignment aln:alignments) generator.processAlignment(aln);
		return varListener.getCalledVariants();
	}

	private CharSequence applyVariants(String consensus, List<CalledGenomicVariant> variants) {
		StringBuilder polishedConsensus = new StringBuilder();
		int l = consensus.length();
		int nextPos = 1;
		int appliedVariants = 0;
		for(CalledGenomicVariant call:variants) {
			if(call.isUndecided()|| call.isHeterozygous()) continue;
			appliedVariants++;
			String [] calledAlleles = call.getCalledAlleles();
			if(nextPos<call.getFirst()) {
				//Fill haplotypes with non variant segment
				String segment = consensus.substring(nextPos-1, call.getFirst()-1);
				polishedConsensus.append(segment);
			}
			polishedConsensus.append(calledAlleles[0]);
			nextPos = call.getLast()+1;
		}
		if(nextPos<=l) {
			//End of a chromosome
			CharSequence nonVarLast = consensus.substring(nextPos-1);
			polishedConsensus.append(nonVarLast);
		}
		log.info("Applied "+appliedVariants+" homozygous alternative variants");
		return new DNASequence(polishedConsensus.toString());
	}
}
