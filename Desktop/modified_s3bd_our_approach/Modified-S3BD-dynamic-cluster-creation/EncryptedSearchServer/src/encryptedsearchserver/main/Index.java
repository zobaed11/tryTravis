/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package encryptedsearchserver.main;

import java.text.DecimalFormat;
import encryptedsearchserver.utilities.Constants;
import encryptedsearchserver.utilities.Util;
import Jama.Matrix;
import javafx.scene.transform.MatrixType;
import javax.management.openmbean.TabularDataSupport;

import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inverted Index and Auxiliary information used to represent documents for searching.
 * Contains the posting list and documents sizes table.
 * Also contains the topic based clusters
 * @author Zobaed
 */
class Index {

    private static File indexFile = null;
    private static File indexFile_cl = null;
    private static File docSizesFile = null;
    private static File clustersDirectory = null; //The folder for clusters
    //The inverted index.  Maps a term to a mapping of files to frequency counts
    public HashMap<String, HashMap<String, Integer>> postingList = null;
    public HashMap<String, HashMap<String, Integer>> postingList2 = null;
    //Map a document to its word count
    public HashMap<String, Long> documentSizes = null;
    //Map a cluster name to its cluster
    //The clusters then contain their own subIndexes
    public HashMap<String, Cluster> clusters = null;
    
    private String indexFileLocation = Constants.utilitiesLocation + File.separator + Constants.indexFileName;
    private String indexFile_cl_Location = Constants.utilitiesLocation + File.separator + Constants.indexFile_cl_Name;
    private String docSizesFileLocation = Constants.utilitiesLocation + File.separator + Constants.docSizesFileName;
    
    //Signifies if the index has been validly partitioned.
    private boolean validPartitions;
    private boolean dirty;
    
    // A list of what clusters we've put into the posting list.  This is checked so we don't overwrite when we don't need to.
    private HashSet<String> clustersPutIntoPostingList;
    
    public Index() {
    	
        postingList = new HashMap<>();
        postingList2 = new HashMap<>();
        clustersPutIntoPostingList = new HashSet<>();
        try {
            //Try to open the files.  If they don't exist, make new ones.
            indexFile = new File(indexFileLocation);
            docSizesFile = new File(docSizesFileLocation);
            indexFile_cl = new File(indexFile_cl_Location);
            indexFile_cl.createNewFile();
            if (!indexFile.exists()) {
                System.out.println("Index File Not Found.  Creating new Index.txt at " + Constants.utilitiesLocation);
                indexFile.createNewFile();
            }
            if (!docSizesFile.exists()) {
                System.out.println("Document Sizes File Note Found.  Creating new DocSizes.txt at " + Constants.utilitiesLocation);
                docSizesFile.createNewFile();
            }
            
            System.out.printf("Loading Document Sizes File... "); // wu
            prepareDocSizesList();
            System.out.println("Done!");
            
            
        } catch (IOException ex) {
            System.out.println(this.getClass().getName() + ": Unable to create new index files.");
        }
    }
    
    /**
     * Prepare the inverted index and all clusters.
     */
    public void prepareWholeIndex() {
        System.out.print("Loading Inverted Index... "); //wu
        preparePostingList(); //wu
        System.out.println("Done!");

        //Now worry about the clusters.
        //Check if there are files in the directory.  We only build clusters if there are.
        List<String> clusterFiles = Util.getAbsoluteFilePathsFromFolder(Constants.clusterLocation);
        if (clusterFiles.size() > 0) { //We have some clusters
        	
            System.out.println("Clusters found.  Reading into clusters..."); // ACTUALLY it means I have cluster folder in utilities. Not necessarily CLUSTER FILES. 
            
            prepareClusters(clusterFiles);
            System.out.println("Done!");
        }
    }
    
    /**
     * A public accessor that just allows an outside user to compile the inverted index.
     */
    public void prepareInvertedIndex() {
        System.out.println("Loading Inverted Index...");
        preparePostingList();
        System.out.println("Done!");
    }
    
    public void prepareAllClusters() {
        //Check if there are files in the directory.  We only build clusters if there are.
        List<String> clusterFiles = Util.getAbsoluteFilePathsFromFolder(Constants.clusterLocation);
        if (clusterFiles.size() > 0) { //We have some clusters
            System.out.println("Clusters found.  Reading into clusters...");
            prepareClusters(clusterFiles);
            System.out.println("Done!");

            for (String clusterName : clusters.keySet())
                System.out.println(clusters.get(clusterName));
        } else {
            System.out.println("No clusters found.  Consider performing a partition.");
        }
    }
    
    /**
     * Loads the given cluster names into memory.
     * NOTE: The names must be in the format "cluster_NAME.txt"
     * This way we don't have to do any additional string processing here.
     * @param clusterNames 
     */
    public void prepareSelectClusters(List<String> clusterFileNames) {
        System.out.print("\nReading selected clusters into memory...  ");
        prepareClusters(clusterFileNames);
        System.out.println("Done!\n");
    }
    
    /**
     * Puts all of the cluster info that has been loaded for search into the
     * posting list for easy search.
     * This is necessary during search, because the ranking engine has to get some
     * info from the posting list.
     * Post-conditions:
     *  The postingList will be filled with whatever sub-indices have been put in
     *  the clusters.
     */
    public void putClustersInPostingList() {
        System.out.println("\nPutting the selected clusters into the main list...");
        for (String cName : clusters.keySet()) {
            // Make sure the posting list does not yet have this cluster.
            if (!clustersPutIntoPostingList.contains(cName)) 
                postingList.putAll(clusters.get(cName).subIndex);
            else 
                System.out.println("Cluster " + cName + " was already in the main list.");
            clustersPutIntoPostingList.add(cName);
        }
        System.out.println("Done!\n");
    }

    /**
     * Reads the contents of the index file into the posting list.
     * Entries in the text file should be formatted as such:
     *  topic|.|filename|.|freq|.|filename|.|freq
     */
    
    private void preparePostingList() {
        
        //Read from the index file
        BufferedReader br;
        String[] lineTokens = new String[10];
        String topic = "";
        int i = 1;
        try {
            br = new BufferedReader(new FileReader(indexFile.getAbsolutePath()));
            String currentLine;
            
            while ((currentLine = br.readLine()) != null) {
                lineTokens = currentLine.split(Constants.regexIndexDelimiter);
                //System.out.println(currentLine); // cancel|.|WET4793.txt|.|16|.|WET1096.txt|.|46|.|WET112.txt|.|6|.|WET5590.txt|.|53
               // System.exit(0);
                
                topic = lineTokens[0];
                // Get rid of those unwanted terms
                if (topic == "WARC"  || topic == "conversion") {
                    System.out.print(" Got rid of " + topic + "... ");
                    continue;
                }
                
                //Get the file names and frequencies
                for (i = 1; i < lineTokens.length; i += 2) {
                    //Make sure we're not working with some weird case that will kill us
                	
                	
//                		if(i<4) System.out.println(lineTokens[i]);
//                		else exit(); TO CHECK .. IT JUST TAKES FILES
                		
                    if (i+1 >= lineTokens.length) continue;  //UNNECESSARY LINE
                    String fileName = lineTokens[i];
                    int frequency = Integer.parseInt(lineTokens[i+1]); //NOW IT TAKES FREQ.
                    //Actually put the data in
                    addToPostingList(topic, fileName, frequency);
                }
            }
            

        } catch (FileNotFoundException ex) {
            System.out.println(this.getClass().getName() + ": Index File Note Found!");
        } catch (IOException ex) {
            System.out.println(this.getClass().getName() + ": Error reading index file!");
        } catch (ArrayIndexOutOfBoundsException ex) {
            System.out.println(this.getClass().getName() + ": Array out of bounds!  Size of array = " + lineTokens.length + " with i = " + i + " with topic = " + topic);
        }
    }
    
    
    
    
    
    
    
    int getNumberOfCluster() {
        int nc=0;
        //Read from the index file
    	 
         //Read from the index file
         BufferedReader br = null, br2 =null;
         BufferedWriter bw;
         String[] lineTokens = new String[10];
         String topic = "";
         String regexIndexDelimiter = "\\|.\\|"; //For reading from the index
         //int i = 1;
        
        
         postingList2 = new HashMap<String, HashMap<String, Integer>> ();
         String[] topicArray = new String[100000];
         
   //for writing a new index file with eliminating all one word terms.      
/*         try {
        	 
        	 br = new BufferedReader (new FileReader (indexFile.getAbsolutePath ()) );
        	// indexFile_cl.createNewFile();
        	 bw = new BufferedWriter(new FileWriter (indexFile_cl) );
        	 
        	 String currentLine;
        	 System.out.println(br.readLine());
        	 String val_topic[];
        	 while( (currentLine=br.readLine() )!= null)
        		 {
        		 
        		 	if ( !currentLine.contains(" ")  && currentLine.charAt(3)!= '|' )
        		 	{
        		 		 bw.write(currentLine);
                		 bw.newLine();
                		 System.out.println(currentLine);
        		 	}
        		 }
        		 
        
        	 System.out.println("bw is closing");
        	 
        	 
        	 
        	 
        	
        	 
         } catch (FileNotFoundException e)
         {
        	 e.printStackTrace();
         }
         catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}   */      
         
   
         
         try {
             try {
            	// br2 = new BufferedReader(new FileReader(indexFile_cl));
            	 br2 = new BufferedReader(new FileReader(indexFile));
             } catch (FileNotFoundException e) {
                 e.printStackTrace();
             }
             String currentLine;
             int tindex = 0;
             String findStr = "|.|";
             while ((currentLine = br2.readLine()) != null) {

                 lineTokens = currentLine.split(regexIndexDelimiter);
                 //System.out.println(currentLine); // cancel|.|WET4793.txt|.|16|.|WET1096.txt|.|46|.|WET112.txt|.|6|.|WET5590.txt|.|53
                 // System.exit(0);

                 topic = lineTokens[0];

                 topicArray[tindex] = topic;
                 tindex++;

                 // Get rid of those unwanted terms
                 if (topic == "WARC" || topic == "conversion") {
                     System.out.print(" Got rid of " + topic + "... ");
                     continue;
                 }
                 
                 //Get the file names and frequencies
                 for (int i = 1; i < lineTokens.length; i += 2) {
                     //System.out.println(lineTokens.length); 

                  
//                 		if(i<4) System.out.println(lineTokens[i]);
//                 		else exit(); TO CHECK .. IT JUST TAKES FILES

                     if (i + 1 >= lineTokens.length) continue;  //UNNECESSARY LINE
                     String fileName = lineTokens[i];
                     int frequency = Integer.parseInt(lineTokens[i + 1]); //NOW IT TAKES FREQ.
                     //Actually put the data in

                     addToPostingList2(topic, fileName, frequency);


                 }
                 
             


             }
             br2.close();
     


             Map<String, Integer> topicIndex_all = new HashMap<>();
             List<String> topicList_all = new ArrayList<>();  // topicList is used to print the matrix
             int index = 0;
             for (String topic_i : postingList2.keySet()) {
                 if (!topicIndex_all.containsKey(topic_i)) {
                     topicIndex_all.put(topic_i, index++);
                     topicList_all.add(topic_i);
                 }
             }
               System.out.println("Total terms:  "+ topicIndex_all.size()); //Topic Index means--> all terms now have unique id

             index = 0;
             Map<String, Integer> documentIndex_all = new HashMap<>();
             for (String topic_i : postingList2.keySet()) {
                 for (String document : postingList2.get(topic_i).keySet()) {
                     if (!documentIndex_all.containsKey(document))
                         documentIndex_all.put(document, index++);
                 }
             }
             System.out.println("Total Docs: "+ documentIndex_all.size());
             int ratio;
             ratio = (int) ( (double)topicIndex_all.size()/ (double) documentIndex_all.size()+1);
             System.out.println(ratio);
             //System.exit(0);
             int will_use_according_to_ratio=ratio*2; 
             
        
             
             try {
                 //br = new BufferedReader(new FileReader("indexFile_mod.txt"));
            	 br = new BufferedReader(new FileReader(indexFile));
             } catch (FileNotFoundException e) {
                 e.printStackTrace();
             }
             topic=null;
             topicArray = new String[40000];
             postingList2.clear();
             //System.out.print(postingList.keySet().size());
             
             tindex=0;
             currentLine=null;

             while ((currentLine = br.readLine()) != null) {
                 // System.out.println(currentLine);



                 if (StringUtils.countMatches(currentLine, findStr)>will_use_according_to_ratio)
                 {
                     lineTokens = currentLine.split(regexIndexDelimiter);

                     topic = lineTokens[0];

                     topicArray[tindex] = topic;
                     tindex++;
                     // System.out.println(tindex);

                     // Get rid of those unwanted terms
                     if (topic == "WARC" || topic == "conversion") {
                         System.out.print(" Got rid of " + topic + "... ");
                         continue;
                     }

                     //Get the file names and frequencies
                     for (int i = 1; i < lineTokens.length; i += 2) {
                         //System.out.println(lineTokens.length); // prints 7 for 'cancel'

                         //Make sure we're not working with some weird case that will kill us
//                 		if(i<4) System.out.println(lineTokens[i]);
//                 		else exit(); TO CHECK .. IT JUST TAKES FILES

                         if (i + 1 >= lineTokens.length) continue;  //UNNECESSARY LINE
                         String fileName = lineTokens[i];
                         int frequency = Integer.parseInt(lineTokens[i + 1]); //NOW IT TAKES FREQ.
                         //Actually put the data in

                         addToPostingList2(topic, fileName, frequency);
                     }

                 }


             }
             System.out.println("Chcker: "+postingList2.keySet().size());
           
             
             
             
             
                
             
             
             Map<String, Integer> topicIndex = new HashMap<>();
             List<String> topicList = new ArrayList<>();  // topicList is used to print the matrix
              index = 0;
              //System.out.println("ZObs: "+ postingList2.keySet().size());
              
              
             for (String topic_i : postingList2.keySet()) {
                 if (!topicIndex.containsKey(topic_i)) {
                     topicIndex.put(topic_i, index++);
                     topicList.add(topic_i);
                 }
             }
             //System.out.println(topicIndex); //Topic Index means--> all terms now have unique id

             index = 0;
             Map<String, Integer> documentIndex = new HashMap<>();
             for (String topic_i : postingList2.keySet()) {
                 for (String document : postingList2.get(topic_i).keySet()) {
                     if (!documentIndex.containsKey(document))
                         documentIndex.put(document, index++);
                 }
             }
            // System.out.println(documentIndex);

             //create and populate the matrix

             double [][] mat = new double[topicIndex.size()][documentIndex.size()];
             double zero_counter=0;
             for (String topic_i : postingList2.keySet()) {

                 // System.exit(0);
                 for (String document : postingList2.get(topic_i).keySet()) {


                     mat[topicIndex.get(topic_i)][documentIndex.get(document)] = postingList2.get(topic_i).get(document);
                   
                 }
             }
             
             //sparsity checking
             for (int row = 0; row < topicIndex.size(); row++) {
                 //System.out.printf("%-30s", topicList.get(row));
                 for (int col = 0; col < documentIndex.size(); col++) {
                     //System.out.printf("%3f ", mat[row][col]);
                	 if(mat[row][col]==0) zero_counter++;
                 }
                 //System.out.println();
             }
             System.out.println(documentIndex.size()+"   "+topicIndex.size());
             System.out.println("Sparsity: "+  (zero_counter*100/(documentIndex.size()*topicIndex.size())));
             //System.exit(0);
             
             
 //Print the matrix to check its values
/*             for (int row = 0; row < topicIndex.size(); row++) {
                 System.out.printf("%-30s", topicList.get(row));
                 for (int col = 0; col < documentIndex.size(); col++) {
                     System.out.printf("%3f ", mat[row][col]);
                 }
                 System.out.println();
             }*/

             //my matrix is ready. Now I will normalize it.



 //generating each row max value for future. It is additional.
            double [] max_all_row_1D = new double[mat.length];
             for (int row = 0; row < mat.length; row++) {
                 double max = 0; // set max to minimum value before starting loop
                 for (int column = 0; column < mat[row].length; column++)
                 {
                    


                     if (mat[row][column] >= max) {
                         max = mat[row][column];
                     }
                 }

                 max_all_row_1D[row]=max;

             }

             //take 1D array to store max column value for each column.

             double [] max_all_column_1D = new double[documentIndex.size()];
             for ( int col = 0; col < documentIndex.size(); col++)
             {
                 double highest = 0;
                 for ( int row = 0; row < mat.length; row++)
                 {
                     if ( col < mat[row].length && mat[row][col] > highest)
                         highest = mat[row][col];
                 }
                 max_all_column_1D[col]=highest;
                 
             }

             for (int ik=0; ik <documentIndex.size(); ik++)
             {
                 //System.out.println( max_all_column_1D[ik]);
             }




 // now we will divide each value with each column's highest value.



             double [] each_row_sum = new double [mat.length]; //for taking row sum

             for (int row = 0; row<mat.length;row++)
             {
                 each_row_sum [row]=0;
                 for (int column=0; column <mat[row].length;column++)
                 {
                     //if (mat[row][column]>0) System.out.println(mat[row][column] + "   prev");
                     mat[row][column]=  mat [row][column]/max_all_column_1D[column];
                    // if (mat[row][column]>0) System.out.println(mat[row][column] + "   dkd" + max_all_column_1D[column]);
                     each_row_sum [row]+=mat[row][column];
                    // each_column_sum[row]+=mat[column][row]; Then we won't get updated value. That's why need to do it seperately in line 254

                 }
             }


             System.out.println( "*************************\n***************************");
             //now print the matrix to check normalization.
         /*    DecimalFormat df2 = new DecimalFormat("#.##");
             for (int row = 0; row < topicIndex.size(); row++) {
                 System.out.printf("%-30s", topicList.get(row));
                 for (int col = 0; col < documentIndex.size(); col++) {
                     System.out.printf(df2.format(mat[row][col]) + "   ");
                 }
                 System.out.println();
             }*/


             double [] each_column_sum = new double [documentIndex.size()];

             for(int column= 0; column< documentIndex.size(); column++)
             {
                 each_column_sum[column] = 0;
                 for(int row= 0; row < mat.length; row++)
                 {
                     each_column_sum[column] += mat[row][column];
                 }

                 //System.out.println(each_column_sum[column]);
             }


             System.out.println( "*************************\n***************************");
             //now print the matrix to check normalization.


 // need to start 2 stage probability now.

 //1st stage
             // I need row sum. I have done it in line 210.

             //1 new matrix for 1st stage.

             double s[][] = new double[topicIndex.size()][documentIndex.size()];
             for (int row =0 ; row< topicIndex.size();row++)
             {
                 for (int col=0 ; col < documentIndex.size(); col++)
                 {
                     s[row][col]= mat[row][col]/each_row_sum[row];
                   
                 }
             }
             // 1st stage is done. Let's print to check.
 System.out.println("1st stage**************");
/*             DecimalFormat df3 = new DecimalFormat(".###");
             for (int row = 0; row < topicIndex.size(); row++) {
                 System.out.printf("%-30s", topicList.get(row));
                 for (int col = 0; col < documentIndex.size(); col++) {
                     System.out.printf(df3.format(s[row][col]) + "   ");
                 }
                 System.out.println();
             }*/
 // 1st stage is correct. Now go to 2nd stage

             System.out.println("1st stage correct. Go 2nd**************");

         //i need column sum. I have done it line 213
     // new matrix for 2nd stage

             double s_prime[][] = new double[topicIndex.size()][documentIndex.size()];


             for (int row =0 ; row< topicIndex.size();row++)
             {
                 for (int col=0 ; col < documentIndex.size(); col++)
                 {
                     s_prime[row][col]= mat[row][col]/each_column_sum[col];

                     
                 }
             }
             
     /*        DecimalFormat df4 = new DecimalFormat(".###");
             for (int row = 0; row < topicIndex.size(); row++) {
                 System.out.printf("%-30s", topicList.get(row));
                 for (int col = 0; col < documentIndex.size(); col++) {
                     System.out.printf(df4.format(s_prime[row][col]) + "   ");
                 }
                 System.out.println();
             }

             System.out.println("2nd stage done and correct");*/

             System.out.println("topic"+ topicIndex.size() + "Document:"+ documentIndex.size() );
             //jama starts here
             
             System.out.println("fdgfdfdgfdgfdgdfgdfg");
             
            double c [] [] = new double [topicIndex.size()][topicIndex.size()];
            System.out.println("Afterreewrewqwfdgdfgdfg");
             
             
             Matrix S = new Matrix(s);
             Matrix S_prime= new Matrix(s_prime);
             Matrix C = new Matrix(c);
             
             C= S.times(S_prime.transpose());
             //C.print(4, 3);
             // calculate number of cluster now ... :D
             double number_of_cluster ;
             number_of_cluster= C.trace();
            
             //int to double conversion

             nc= (int) number_of_cluster; 
             System.out.println(nc);
             System.exit(0);
             
             


          //Trying JAMA instead of ND4J
           //  Matrix A = new Matrix (mat); // need to convert the int array to double.



             // System.out.println(postingList);
         } catch (IOException e) {
             e.printStackTrace();
         }
         return nc;

    }
    
    
    public void exit()
    {
    		System.exit(0);
    }
    
    /**
     * Add Info To The Posting List.
     * Adds the given file and frequency to the topic.
     * Will make a new hash map if there is none in the place of the topic.
     * @param topic
     * @param fileName
     * @param frequency 
     */
    
    public void addToPostingList(String topic, String fileName, int frequency) {
        HashMap<String, Integer> files = postingList.get(topic);
        
        //Check if we need to make a new map.
        if (files == null || files.isEmpty()) {
            files = new HashMap<>(); //IT WILL CREATE FIRST TIME //I AM HERE
          
        }
        files.put(fileName, frequency);
        postingList.put(topic, files);
        

    }
    
    
    //for CLUSTER PREDICTION
    public void addToPostingList2(String topic, String fileName, int frequency) {
        HashMap<String, Integer> files = postingList2.get(topic);
        
        //Check if we need to make a new map.
        if (files == null || files.isEmpty()) {
            files = new HashMap<>(); //IT WILL CREATE FIRST TIME //I AM HERE
          
        }
        files.put(fileName, frequency);
        postingList2.put(topic, files);
      

    }
    
    
    

    
    /**
     * Prepare the List of Document Sizes.
     * Goes through DocSizes.txt and puts the file names and their appropriate 
     * sizes into a hash map.
     * 
     * The file should be organized as follows:
     *  DocumentName|.|size
     */
    private void prepareDocSizesList() {
        documentSizes = new HashMap<>();
        BufferedReader br;
        String currentLine;
        
        try {
            //Open the file for reading
            br = new BufferedReader(new FileReader(docSizesFile.getAbsolutePath()));
            
            while ((currentLine = br.readLine()) != null) {
                //Get the file name and length
                String[] tokens = currentLine.split(Constants.regexIndexDelimiter);
                String fileName = tokens[0];
                long wordCount = Long.parseLong(tokens[1]);
                
                addToDocSizes(fileName, wordCount);
            }
            
            br.close();
        } catch (FileNotFoundException ex) {
            System.err.println(this.getClass().getName() + ": DocSizes.txt file not found");
        } catch (IOException ex) {
            System.err.println(this.getClass().getName() + ": Error Reading DocSizes.txt file");
        }
    }

    /**
     * Add the given data to the document sizes map.
     * @param fileName
     * @param wordCount 
     */
    public void addToDocSizes(String fileName, long wordCount) {
        documentSizes.put(fileName, wordCount);
    }
    
    /**
     * Load in data from the cluster files into our cluster list.
     * Entries in the cluster files should be formatted the same as the postingList.
     * @param clusterFileNames The relative names of the files in the clusters directory
     */
    public void prepareClusters(List<String> clusterFileNames) {
        clusters = new HashMap<>();
        
        //Read from the files, one at a time
        BufferedReader br;
        try {
            for (String fileName : clusterFileNames) {
                //Setup to read a single file.
                //Important to note that we don't know which file til we read the first line and find the name.
                br = new BufferedReader(new FileReader(fileName));
                //Gotta create the new cluster to store that new data
                Cluster cluster = new Cluster();
                
                String name;
                name = br.readLine();
                cluster.name = name;
                
                String currentLine;
                
                while ((currentLine = br.readLine()) != null) {
                    String[] lineTokens = currentLine.split(Constants.regexIndexDelimiter);
                    String topic = lineTokens[0];
                
                    //Get the file names and frequencies
                    for (int i = 1; i < lineTokens.length; i += 2) {
                        String file = lineTokens[i];
                        int frequency = Integer.parseInt(lineTokens[i+1]);
                        //Actually put the data in
                        addToCluster(cluster, topic, file, frequency);
                    }
                }
                
                //At this point the cluster should be all set up.  Now put it in the map.
                clusters.put(name, cluster);
            }
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Index.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Index.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * Add data to a cluster.
     * Pretty much the same as adding to the posting list.  Just you need to 
     * specify the cluster
     * @param cluster
     * @param topic
     * @param fileName
     * @param frequency 
     */
    public void addToCluster(Cluster cluster, String topic, String fileName, int frequency) {
        HashMap<String, Integer> files = cluster.subIndex.get(topic);
        //Check if we need to make a new map.
        if (files == null || files.isEmpty()) {
            files = new HashMap<>();
        }
        
        files.put(fileName, frequency);
        cluster.subIndex.put(topic, files);
    }
    
    
    // ---------- WRITING TO FILES ------------
    
    /**
     * Writes the contents of the posting list back to the original index file.
     * Should write in the same style that it was read from.
     */
    public void writePostingListToIndexFile() {
        BufferedWriter bw;
        
        try {
            bw = new BufferedWriter (new OutputStreamWriter (new FileOutputStream(indexFile)));
            
            //Iterate through all keys (topics, writing all of their values to the file.
            for (String topic : postingList.keySet()) {
                //Use string builder to save space
                StringBuilder lineSB = new StringBuilder();
                lineSB.append(topic);
                
                //Get and add all files and frequencies associated with that topic
                HashMap<String, Integer> files = postingList.get(topic);
                for (String file : files.keySet()) {
                    lineSB.append(Constants.indexDelimiter).append(file).append(Constants.indexDelimiter).append(files.get(file));
                }
                
                bw.write(lineSB.toString());
                bw.newLine();
            }
            
            bw.close();
        } catch (FileNotFoundException ex) {
            System.err.println(this.getClass().getName() + ": Index.txt file now found");
        } catch (IOException ex) {
            System.err.println(this.getClass().getName() + ": Error writing to Index.txt");
        }
    }
    
    /**
     * Writes the contents of the docSizes map back to the original DocSizes file.
     * Should write in the same style that it was read from.
     */
    public void writeDocSizesToFile() {
        BufferedWriter bw;
        try {
            bw = new BufferedWriter (new OutputStreamWriter (new FileOutputStream(docSizesFile)));
            
            //Iterate through all files
            for (String file : documentSizes.keySet()) {
                String line = file + Constants.indexDelimiter + documentSizes.get(file);
                bw.write(line);
                bw.newLine();
            }
            
            bw.close();
        } catch (FileNotFoundException ex) {
            System.err.println(this.getClass().getName() + ": DocSizes.txt file now found");
        } catch (IOException ex) {
            System.err.println(this.getClass().getName() + ": Error writing to DocSizes.txt");
        }
    }
    
    @Override
    public String toString() {
        return "IndexFile{" + "postingList=" + postingList + '}' + "\nDoc Sizes={" + documentSizes + ')';
    }
}
