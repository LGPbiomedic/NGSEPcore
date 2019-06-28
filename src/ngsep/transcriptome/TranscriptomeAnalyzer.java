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
package ngsep.transcriptome;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import ngsep.genome.GenomicRegion;
import ngsep.genome.ReferenceGenome;
import ngsep.main.CommandsDescriptor;
import ngsep.main.OptionValuesDecoder;
import ngsep.main.ProgressNotifier;
import ngsep.math.Distribution;
import ngsep.sequences.QualifiedSequence;
import ngsep.sequences.QualifiedSequenceList;
import ngsep.sequences.io.FastaSequencesHandler;
import ngsep.transcriptome.io.GFF3TranscriptomeHandler;
import ngsep.transcriptome.io.GFF3TranscriptomeWriter;

/**
 * @author Tatiana Garcia
 * @author Jorge Duitama
 */
public class TranscriptomeAnalyzer {

	public static final int DEF_MIN_PROTEIN_LENGTH=0;
	private ReferenceGenome genome;
	
	private boolean selectCompleteProteins = false;
	
	private int minProteinLength=DEF_MIN_PROTEIN_LENGTH;
	
	private Logger log = Logger.getLogger(TranscriptomeAnalyzer.class.getName());
	
	private ProgressNotifier progressNotifier=null;
	
	private ProteinTranslator translator=  new ProteinTranslator();
	
	public static void main(String[] args) throws Exception {
		TranscriptomeAnalyzer instance = new TranscriptomeAnalyzer();
		int i = CommandsDescriptor.getInstance().loadOptions(instance, args);
		String transcriptomeFile = args[i++];
		String outPrefix = args[i++];
		instance.processTranscriptome(transcriptomeFile, outPrefix);
	}

	/**
	 * @return the log
	 */
	public Logger getLog() {
		return log;
	}

	/**
	 * @param log the log to set
	 */
	public void setLog(Logger log) {
		this.log = log;
	}

	public ProgressNotifier getProgressNotifier() {
		return progressNotifier;
	}

	public void setProgressNotifier(ProgressNotifier progressNotifier) {
		this.progressNotifier = progressNotifier;
	}

	/**
	 * @return the genome
	 */
	public ReferenceGenome getGenome() {
		return genome;
	}

	/**
	 * @param genome the genome to set
	 */
	public void setGenome(ReferenceGenome genome) {
		this.genome = genome;
	}
	
	

	/**
	 * @return the selectCompleteProteins
	 */
	public boolean isSelectCompleteProteins() {
		return selectCompleteProteins;
	}

	/**
	 * @param selectCompleteProteins the selectCompleteProteins to set
	 */
	public void setSelectCompleteProteins(boolean selectCompleteProteins) {
		this.selectCompleteProteins = selectCompleteProteins;
	}
	public void setSelectCompleteProteins(Boolean selectCompleteProteins) {
		this.setSelectCompleteProteins(selectCompleteProteins.booleanValue());
	}
	
	/**
	 * @return the minProteinLength
	 */
	public int getMinProteinLength() {
		return minProteinLength;
	}

	/**
	 * @param minProteinLength the minProteinLength to set
	 */
	public void setMinProteinLength(int minProteinLength) {
		this.minProteinLength = minProteinLength;
	}
	
	public void setMinProteinLength(String value) {
		this.setMinProteinLength((int)OptionValuesDecoder.decode(value, Integer.class));
	}
	
	

	public void processTranscriptome(String transcriptomeFile, String outPrefix) throws IOException {
		Distribution geneLengthDist = new Distribution (0,9999,200);
		Distribution transcriptLengthDist = new Distribution (0,4999,200);
		Distribution transcriptsPerGeneDist = new Distribution (0,20,1);
		Distribution numberOfExonsDist = new Distribution (0,50,1);
		Distribution cdsLengthDist = new Distribution (0,4999,200);
		Distribution proteinLengthDist = new Distribution (0,1999,100);
		
		
		//Load transcriptome
		GFF3TranscriptomeHandler gff3Handler = new GFF3TranscriptomeHandler();
		gff3Handler.setLog(log);
		QualifiedSequenceList sequenceNames=null;
		if(genome!=null) {
			sequenceNames = genome.getSequencesMetadata();
			gff3Handler.setSequenceNames(sequenceNames);
		}
		Transcriptome transcriptome = gff3Handler.loadMap(transcriptomeFile); 			
		if(genome!=null) transcriptome.fillSequenceTranscripts(genome, log);
		else sequenceNames = gff3Handler.getSequenceNames();
		
		List<Transcript> transcriptsList = transcriptome.getAllTranscripts();
		Set<String> visitedGeneIDs = new HashSet<>();
		QualifiedSequenceList proteome = new QualifiedSequenceList();
		QualifiedSequenceList codingSequences = new QualifiedSequenceList();
		QualifiedSequenceList cdnaSequences = new QualifiedSequenceList();
		
		Transcriptome filteredTranscriptome = null;
		if(selectCompleteProteins || minProteinLength>DEF_MIN_PROTEIN_LENGTH) filteredTranscriptome = new Transcriptome(sequenceNames);
		
		int n = 0;
		for(Transcript t: transcriptsList) {
			//Collect transcript structure statistics
			int start = t.getCodingRelativeStart();
			int end = t.getCodingRelativeEnd();
			String geneId= t.getGeneId(); 
			transcriptLengthDist.processDatapoint(t.length());
			List<GenomicRegion> exons=t.getRawExons();
			numberOfExonsDist.processDatapoint(exons.size());
			
			if(!visitedGeneIDs.contains(geneId)) {
				//Collect gene statistics
				List<Transcript> geneTranscripts = transcriptome.getTranscriptsByGene(geneId);
				if(geneTranscripts==null) {
					log.info("Gene not found for transcript with id"+t.getId()+" gene id: "+geneId);
					continue;
				}
				transcriptsPerGeneDist.processDatapoint(geneTranscripts.size());
				geneLengthDist.processDatapoint(t.getGene().length());
				visitedGeneIDs.add(geneId);
			}
			
			String comments = t.getGeneId();
			if(t.getGeneName()!=null) comments+= " "+t.getGeneName();
			CharSequence cdnaSequence = t.getCDNASequence();
			if(cdnaSequence==null) {
				continue;
			}
			QualifiedSequence qCDNA = new QualifiedSequence(t.getId(),cdnaSequence);
			qCDNA.setComments(comments);
			cdnaSequences.add(qCDNA);
			
			String protein = null;
			if(!t.isCoding()) {
				if(filteredTranscriptome!=null) filteredTranscriptome.addTranscript(t);
				continue;
			}
			Codon startCodon = t.getStartCodon();
			if(startCodon==null) {
				log.info("Transcript "+t.getId()+" has an invalid start codon. Length: "+cdnaSequence.length()+" start: "+start);
				continue;
			}
			else if(!startCodon.isStart()) log.info("Transcript "+t.getId()+" does not have a standard start codon. Codon: "+startCodon.getRnaSequence()+" Length: "+cdnaSequence.length()+"start: "+start);
			Codon stopCodon = t.getStopCodon();
			if(stopCodon==null) log.info("Transcript "+t.getId()+" has an invalid stop codon. Length: "+cdnaSequence.length()+" end: "+end);
			else if(!stopCodon.isStop()) log.info("Transcript "+t.getId()+" does not have a standard stop codon. Codon: "+stopCodon.getRnaSequence()+" Length: "+cdnaSequence.length()+" end: "+end);
			
			// Process cds sequence
			CharSequence cds = t.getCDSSequence();
			if(cds == null) {
				log.info("Invalid coding sequence for transcript "+t.getId()+" Length: "+cdnaSequence.length()+"start: "+start+" end: "+end);
				continue;
			}
			QualifiedSequence qCDS = new QualifiedSequence(t.getId(),cds);
			qCDS.setComments(comments);
			codingSequences.add(qCDS);
			cdsLengthDist.processDatapoint(cds.length());
			
			// Process protein sequence
			protein = t.getProteinSequence(translator);
			if(protein == null) {
				log.info("Invalid protein for transcript "+t.getId()+" Length: "+cdnaSequence.length()+"start: "+start+" end: "+end);
				continue;
			}
			proteinLengthDist.processDatapoint(protein.length());
			QualifiedSequence qp = new QualifiedSequence(t.getId(),protein);
			qp.setComments(comments);
			proteome.add(qp);
			
			if(filteredTranscriptome==null) continue;
			if(protein.length()<minProteinLength) continue;
			boolean complete = startCodon.isStart() && stopCodon!=null && stopCodon.isStop();
			if (selectCompleteProteins && !complete) continue;
			filteredTranscriptome.addTranscript(t);
			n++;
			if (progressNotifier!=null && n%100==0) {
				int progress = n/100;
				if (!progressNotifier.keepRunning(progress)) {
					return;
				}
			}
		}
		try (PrintStream out = new PrintStream(outPrefix+"_stats.txt")) {
			out.println("Gene length"); 
			geneLengthDist.printDistributionInt(out);
			out.println();
			out.println("Transcript length");
			transcriptLengthDist.printDistributionInt(out);
			out.println();
			out.println("Number of exons");
			numberOfExonsDist.printDistributionInt(out);
			out.println();
			out.println("Transcripts per gene");
			transcriptsPerGeneDist.printDistributionInt(out);
			if(codingSequences.size()>0) {
				out.println();
				out.println("CDS length");
				cdsLengthDist.printDistributionInt(out);
			}
			if(proteome.size()>0) {
				out.println();
				out.println("Protein length");
				proteinLengthDist.printDistributionInt(out);
			}
		}
		if(cdnaSequences.size()>0) {
			FastaSequencesHandler handler = new FastaSequencesHandler();
			try (PrintStream out = new PrintStream(outPrefix+"_cdna.fa")) {
				handler.saveSequences(cdnaSequences, out, 100);
			}
		}
		if(codingSequences.size()>0) {
			FastaSequencesHandler handler = new FastaSequencesHandler();
			try (PrintStream out = new PrintStream(outPrefix+"_cds.fa")) {
				handler.saveSequences(codingSequences, out, 100);
			}
		}
		if(proteome.size()>0) {
			FastaSequencesHandler handler = new FastaSequencesHandler();
			try (PrintStream out = new PrintStream(outPrefix+"_proteins.fa")) {
				handler.saveSequences(proteome, out, 100);
			}
			
		}
		if(filteredTranscriptome!=null) {
			GFF3TranscriptomeWriter gff3Writer = new GFF3TranscriptomeWriter();
			try (PrintStream out = new PrintStream(outPrefix+"_filtered.gff3")) {
				gff3Writer.printTranscriptome(filteredTranscriptome, out);
			}
		}
		
	}
}
