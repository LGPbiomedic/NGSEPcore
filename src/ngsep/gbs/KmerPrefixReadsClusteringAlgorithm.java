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
package ngsep.gbs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import ngsep.alignments.ReadAlignment;
import ngsep.discovery.PileupRecord;
import ngsep.main.CommandsDescriptor;
import ngsep.main.OptionValuesDecoder;
import ngsep.main.ProgressNotifier;
import ngsep.sequences.DNASequence;
import ngsep.sequences.DNAShortKmer;
import ngsep.sequences.DNAShortKmerClusterMap;
import ngsep.sequences.RawRead;
import ngsep.sequences.io.FastqFileReader;
import ngsep.sequencing.ReadsDemultiplex;

/**
 * @author Jorge Gomez
 * @author Jorge Duitama
 * @author Andrea Parra
 */
public class KmerPrefixReadsClusteringAlgorithm {

	private Logger log = Logger.getLogger(ReadsDemultiplex.class.getName());
	private ProgressNotifier progressNotifier = null;
	
	public static final int DEF_KMER_LENGTH = 31;
	public static final int DEF_START = 8;
	public static final int DEF_MAX_READS_IN_MEMORY = 1000000;
	public static final String DEF_REGEXP_SINGLE="<S>.fastq.gz";
	public static final String DEF_REGEXP_PAIRED="<S>_<N>.fastq.gz";
	
	private String inputDirectory=".";
	private String outPrefix="./output";
	private int kmerLength = DEF_KMER_LENGTH;
	private Pattern regexp=Pattern.compile(DEF_REGEXP_SINGLE);
	private Map<String, String> filenamesBySampleId1=new HashMap<>();
	private Map<String, String> filenamesBySampleId2=new HashMap<>();
	private Map<Integer, String> clusterRefSeqMap = new HashMap<>();  //hashmap of cluster ids and ref seqs.
	private DNAShortKmerClusterMap kmersMap;
	
	
	public static void main(String[] args) throws Exception {
		KmerPrefixReadsClusteringAlgorithm instance = new KmerPrefixReadsClusteringAlgorithm();
		int i = CommandsDescriptor.getInstance().loadOptions(instance, args);
		instance.inputDirectory = args[i++];
		instance.outPrefix = args[i++];
		instance.run();
	}
	
	public void setProgressNotifier(ProgressNotifier progressNotifier) { 
		this.progressNotifier = progressNotifier;
	}
	
	public ProgressNotifier getProgressNotifier() {
		return progressNotifier;
	}
	
	public Logger getLog() {
		return log;
	}

	public void setLog(Logger log) {
		this.log = log;
	}

	/**
	 * @return the kmerLength
	 */
	public int getKmerLength() {
		return kmerLength;
	}
	/**
	 * @param kmerLength the kmerLength to set
	 */
	public void setKmerLength(int kmerLength) {
		this.kmerLength = kmerLength;
	}
	
	public void setKmerLength(String value) {
		setKmerLength((int)OptionValuesDecoder.decode(value, Integer.class));
	}

	public void run() throws IOException {
//		loadFilenamesAndSamples();
//		log.info("Loaded "+filenamesBySampleId1.size()+" samples");
//		buildKmersMap();
//		log.info("Built kmers map with "+kmersMap.size()+" clusters");
//		List<String> clusteredReadsFilenames = clusterReads();
		List<String> clusteredReadsFilenames = debug();
		log.info("Clustered reads");
		callVariants(clusteredReadsFilenames);
		log.info("Called variants");
		printStatistics();
		log.info("Process finished");
	}
	
	private List<String> debug() {		
		log.info("Skipping to call variants");
		List<String> clusteredReadsFilenames = List.of("trial_clusteredReads_0.fastq.gz");
		return clusteredReadsFilenames;
	}

	private void loadFilenamesAndSamples() {
		File[] files = (new File(inputDirectory)).listFiles();
		for(File f : files) {
			String filename = f.getName();
			//TODO: Use pattern
			int i = filename.indexOf(".fastq");
			if(i>=0) {
				String sampleId = filename.substring(0, i);
				filenamesBySampleId1.put(sampleId, f.getAbsolutePath());
			}
		}
	}
	
	
	public void buildKmersMap() throws IOException {
		kmersMap = new DNAShortKmerClusterMap();
		for(String filename:filenamesBySampleId1.values()) {
			addKmersFromFile(filename);
		}
	}

	private void addKmersFromFile(String filename) throws IOException {
		int readCount = 0;
		try (FastqFileReader openFile = new FastqFileReader(filename);) {
			Iterator<RawRead> reader = openFile.iterator();
			while(reader.hasNext()) {
				RawRead read = reader.next();
				String s = read.getSequenceString();
				if(DEF_START + kmerLength>s.length()) continue;
				String prefix = s.substring(DEF_START,DEF_START + kmerLength);
				if(DNASequence.isDNA(prefix)) {
					kmersMap.addOcurrance(new DNAShortKmer(prefix));
					readCount++;
				}
			}
		}
		log.info("Processed a total of " + readCount + " reads for file: "+filename);
		
	}
	public List<String> clusterReads() throws IOException {
		ClusteredReadsCache clusteredReadsCache = new ClusteredReadsCache(); 
		for(String sampleId:filenamesBySampleId1.keySet()) {
			String filename1 = filenamesBySampleId1.get(sampleId);
			String filename2 = filenamesBySampleId2.get(sampleId);
			if(filename2 == null) {
				log.info("Clustering reads from " + filename1);
				clusterReadsSingleFile (sampleId, filename1, clusteredReadsCache);
			} else {
				clusterReadsPairedEndFiles (sampleId, filename1, filename2, clusteredReadsCache);
			}
		}
		clusteredReadsCache.dump(outPrefix);
		return clusteredReadsCache.getClusteredReadFiles();
	}

	private void clusterReadsSingleFile(String sampleId, String filename, ClusteredReadsCache clusteredReadsCache) throws IOException {
		int unmatchedReads = 0;
		int count = 1;
		try (FastqFileReader openFile = new FastqFileReader(filename);) {
			Iterator<RawRead> reader = openFile.iterator();
			while(reader.hasNext()) {
				RawRead read = reader.next();
				String s = read.getSequenceString();
				if(DEF_START + kmerLength>s.length()) continue;
				String prefix = s.substring(DEF_START,DEF_START + kmerLength);
				if(!DNASequence.isDNA(prefix)) continue;
				Integer clusterId = kmersMap.getCluster(new DNAShortKmer(prefix));
				if(clusterId==null) {
					unmatchedReads++;
					continue;
				}
				clusteredReadsCache.addSingleRead(clusterId, new RawRead(sampleId+"_"+clusterId+"_"+read.getName(), s, read.getQualityScores()));
				if(clusteredReadsCache.getTotalReads()>=DEF_MAX_READS_IN_MEMORY) {
					log.info("dumping reads");
					clusteredReadsCache.dump(outPrefix);
				}
				count++;
			}
			log.info(Integer.toString(unmatchedReads) + " reads remained unmatched for file: " + filename);
			log.info(Integer.toString(count) + " reads were succesfully matched for file: " + filename);
		}
	}

	private void clusterReadsPairedEndFiles(String sampleId, String filename1, String filename2, ClusteredReadsCache clusteredReadsCache) throws IOException {
		// TODO Auto-generated method stub
		return;
	}
	

	public void callVariants(List<String> clusteredReadsFilenames) throws IOException {
		// TODO Implement. Load in parallel clustered read files. Align reads within clusters build pileups and call variants
		// For each cluster
		//     create representative sequence
		//     For each read within the cluster create a ReadAlignment. first=1, last=read length, flags=0. Set characters and quality scores
		//     For each position in the representative sequence create a pileup record with cluster id as sequence name and position =i
		
		//     Add the alignments to the pileup record
		//     Use VariantPileuipListener to discover variants from the pileup record for the discovery step variant=null
		

		int maxReadLength = 0;
		int numFiles = 0;
		for(String filename:clusteredReadsFilenames) {
			int longestReadOnFile = repSeqLength(filename);
			if(maxReadLength < longestReadOnFile) {
				maxReadLength = longestReadOnFile;
			}
			numFiles++;
		}
		log.info(Integer.toString(numFiles) + " files were read.");
		log.info("Longest read found: " + Integer.toString(maxReadLength));
		ReadClusters readClusters = new ReadClusters(maxReadLength, numFiles);
		int fileIndex = 0;
		for(String filename:clusteredReadsFilenames) {
			readClusters.addFile(filename, fileIndex);
			readClusteredReads(filename, readClusters);
			fileIndex++;
		}
	}
	
	public int repSeqLength(String filename) throws IOException {
		int maxLength = 0;
		try (FastqFileReader openFile = new FastqFileReader(filename);) {
			Iterator<RawRead> reader = openFile.iterator();
			while(reader.hasNext()) {
				RawRead read = reader.next();
				String s = read.getSequenceString();
				if(s.length()>maxLength) {
					maxLength = s.length();
				}
			}
		}
		return maxLength;
	}
	
	public void readClusteredReads(String filename, ReadClusters readClusters) throws IOException {
		Integer oldClusterId = 0;
		Integer newClusterId;
		String refSeqByClusterId;
		String refSeq;
		int refSeqId;
		int k;
		try (FastqFileReader openFile = new FastqFileReader(filename);) {
			Iterator<RawRead> reader = openFile.iterator();
			while(reader.hasNext()) {
				RawRead read = reader.next();
				String s = read.getSequenceString();
				newClusterId = Integer.parseInt(read.getName().split("_")[1]);
				k = newClusterId;
				if(newClusterId != oldClusterId) {
					log.info(Integer.toString(newClusterId) + ", " + Integer.toString(oldClusterId));
					//readClusters.reportClusterCompletionInFile(filename, oldClusterId);
					refSeq = readClusters.calcRepSeq(oldClusterId);
					log.info("Ref seq for cluster: " + Integer.toString(oldClusterId) + ": " + refSeq);
					processCluster(oldClusterId, refSeq, readClusters);
				} 
				readClusters.addSequenceToCluster(k, s);
				oldClusterId = newClusterId;
				
			}
		}
	}
	
	private void processCluster(int k, String refSeq, ReadClusters readClusters) {
		List<ReadAlignment> clusterReadAlignments = readClusters.getClusterReadAlignments(k);
		for(int i=0; i<refSeq.length(); i++) {
			PileupRecord clusterPileUp = new PileupRecord(Integer.toString(k), i);
			for(ReadAlignment alignment:clusterReadAlignments) {
				clusterPileUp.addAlignment(alignment);
			}
		}
	}
	
	private void printStatistics() {
		// TODO Implement. Create an output file with process statistics
		
	}
	
	/**
	 * 
	 */
	public void readClustersFromFile(String filename) throws IOException {
		int readCount = 0;
		try (FastqFileReader openFile = new FastqFileReader(filename);) {
			Iterator<RawRead> reader = openFile.iterator();
			while(reader.hasNext()) {
				RawRead read = reader.next();
				String s = read.getSequenceString();
				if(DEF_START + kmerLength>s.length()) continue;
				String prefix = s.substring(DEF_START,DEF_START + kmerLength);
				if(DNASequence.isDNA(prefix)) {
					kmersMap.addOcurrance(new DNAShortKmer(prefix));
					readCount++;
				}
			}
		}
		log.info("Processed a total of " + readCount + " reads for file: "+filename);	
	}
}

class ReadClusters {
	private Logger log = Logger.getLogger(ReadsDemultiplex.class.getName());
	
	private Map<String, Integer> completionInfo = new HashMap<>();
	private Map<Integer, int[][]> repSeqTable = new TreeMap<>();
	private Map<Integer, List<ReadAlignment>> clusterReadAlignments = new TreeMap<>();
	private int longestRead;
	private int totalNumFiles;
	private Integer largestProcessedCluster = null;
	private Integer[] completionTable; 
	               
	public ReadClusters(int longestRead, int totalNumFiles)      
	{                                                                 
		this.longestRead = longestRead;                   // this could be done for each cluster  
		this.totalNumFiles = totalNumFiles;
		this.completionTable = new Integer[totalNumFiles]; 
	}
	
	public int[][] getClusterTable(int k) {
		return repSeqTable.get(k);
		
	}
	
	public List<ReadAlignment> getClusterReadAlignments(int k) {
		return clusterReadAlignments.get(k);
	}
	
	public void addFile(String filename, int fileIndex) {
		completionInfo.put(filename, fileIndex);
	}
	
	public void addSequenceToCluster(int k, String s) {
		//Add readAlignment
		List<ReadAlignment> alignments = clusterReadAlignments.get(k);
		ReadAlignment clusterReadAlignment = new ReadAlignment(Integer.toString(k), 1, s.length(), s.length(), 0);
		alignments.add(clusterReadAlignment);
		
		int[][] clusterTable = repSeqTable.get(k);
		if(clusterTable==null) {
			clusterTable = new int[this.longestRead][DNASequence.BASES_ARRAY.length];			
		}
		int[][] newClusterTable = addRead(s, clusterTable);
		repSeqTable.put(k, newClusterTable);
		
		
	}
	
	public void printClusterTable(int k) {
		log.info(Arrays.deepToString(repSeqTable.get(k)).replace("], ", "]\n"));
	}
	
	private int[][] addRead(String s, int[][] clusterTable) {
		for(int i=0; i<s.length(); i++) {
			if(!DNASequence.isInAlphabeth(s.charAt(i))) {
				continue;
			}
			int j = DNASequence.BASES_STRING.indexOf(s.charAt(i));
			clusterTable[i][j]++;
		}
		return clusterTable;
	}
	
	public String calcRepSeq(int k) {
		char[] repSeq = new char[this.longestRead];
		String repSequence = "";
		
		int[][] clusterTable = repSeqTable.get(k);
		for(int i = 0; i < this.longestRead; i++) {
			int max = 0;
			for(int j = 0; j < DNASequence.BASES_STRING.length(); j++) {
				int next = clusterTable[i][j];
				if((max <= next) && (next != 0)){
					repSeq[i] = DNASequence.BASES_STRING.charAt(j);
					max = next;
				}
			}
			repSequence += repSeq[i];
		}
		return repSequence;
	}
	
	private double calcAvgHammingDistance(int k) {
		//TODO
		double avgHammingDist = 0.0;
		
		return avgHammingDist;
	}
	
	public void reportClusterCompletionInFile(String filename, int k) {
		log.info("File " + filename + " done with cluster " + Integer.toString(k));
		int indexOfFile = completionInfo.get(filename);
		completionTable[indexOfFile] = k;
	}
	
	public String checkGeneralClusterCompletion() {
		Integer candidateCluster = 0;
		if(largestProcessedCluster != null) {
			candidateCluster = largestProcessedCluster + 1;
		}
		
		boolean clusterComplete = true;
		for(int i = 0; i < totalNumFiles; i++) {
			if (completionTable[i] == null) {
				clusterComplete = false;
				break;
			}
			if(candidateCluster > completionTable[i])  {
				clusterComplete = false;
				break;
			}
		}
		if(clusterComplete) {
			String seq = calcRepSeq(candidateCluster);
			double hamDist = calcAvgHammingDistance(candidateCluster);
			log.info("Cluster " + Integer.toString(candidateCluster) + " was completed.");
			log.info("Reference sequence: " + seq);
			largestProcessedCluster = candidateCluster;
			return seq + "_" + Integer.toString(candidateCluster);
		} else {
			return null;
		}	
	}
}

class ClusteredReadsCache {
	private Map<Integer,List<RawRead>> clusteredReadsCache = new TreeMap<>();
	private Map<Integer, Map<String, String>> clusterInfo = new TreeMap<>();
	private int totalReads = 0;
	private List<String> outFiles = new ArrayList<>();
	
	public void addSingleRead(int k, RawRead read) {
		List<RawRead> readsClusterK = clusteredReadsCache.get(k);
		if(readsClusterK==null) {
			readsClusterK = new ArrayList<>();
			clusteredReadsCache.put(k, readsClusterK);
		}
		readsClusterK.add(read);
		totalReads++;
	}
	
	public void addClusterInfo(int k, String key, String value) {
		Map<String, String> info = clusterInfo.get(k);
		if(clusterInfo == null) {
			info = new HashMap<>();		
		}
		info.put(key, value);
	}
	
	public Map<String, String> getClusterInfo(int k) {
		return clusterInfo.get(k);
	}
	
	public List<String> getClusteredReadFiles() {
		return outFiles;
	}
	
	public List<RawRead> getClusterReads(int k) {
		return clusteredReadsCache.get(k);
	}
	
	/**
	 * @return the totalReads
	 */
	public int getTotalReads() {
		return totalReads;
	}
	
	/**
	 * Dumps the cache to the file with the given name and clears this cache
	 * @param outPrefix prefix of the file to dump the cache
	 */
	public void dump(String outPrefix) throws IOException {
		int number = outFiles.size();
		String singleFilename = outPrefix+"_clusteredReads_"+number+".fastq.gz";
		outFiles.add(singleFilename);
		try (OutputStream os = new FileOutputStream(singleFilename);
			 GZIPOutputStream gos = new GZIPOutputStream(os);
			 PrintStream out = new PrintStream(gos);) {
			for(List<RawRead> readsCluster:clusteredReadsCache.values()) {
				for(RawRead read:readsCluster) {
					read.save(out);
				}
			}
		}
		clusteredReadsCache.clear();
		totalReads = 0;
	}
}
