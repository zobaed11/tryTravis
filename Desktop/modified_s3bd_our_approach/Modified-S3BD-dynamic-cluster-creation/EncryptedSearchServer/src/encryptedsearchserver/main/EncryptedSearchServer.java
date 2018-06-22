/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package encryptedsearchserver.main;
import static java.lang.System.gc;

import encryptedsearchserver.utilities.Config;
import java.util.Arrays;
import java.util.Scanner;

/**
 *
 * @author Jason
 */
public class EncryptedSearchServer {

   Index index;
    
    /**
     * @param args Arguments for what mode the server should launch into
     */
    public static void main(String[] args) {
        gc();
        if (args.length == 0) {
            args = getUserInput();
        }
        
        EncryptedSearchServer ess = new EncryptedSearchServer(args);
    }
    
    public EncryptedSearchServer(String[] args) {
        //Load properties
        Config.loadProperties();
        
        //Switch based on what the user wants to do
        switch(args[0]) {
            case "-u":
                upload(args[1]);
                break;
            case "-s":
                search();
                break;
            case "-p":
                partition();
                break;
            case "-a":
                sendAbstracts();
            default:
                System.out.println("Unsupported operation requested");
        }
    }
    
    private static String[] getUserInput() {
        String[] args = new String[2];
        System.out.println("Welcome to the Threaded S3C server");
        System.out.print("What would you like to do?  Options: \n"
                + "\tRetrieve Uploaded Documents (store and merge into index) -u\n"
                + "\tRetrieve and perform search query -s\n"
                + "\tPartition Index -p\n"
                + "\tSend Abstracts -a\n"
                + "Choice: ");
        
        //Get input
        Scanner scan;
        scan = new Scanner(System.in);
        
        String choice = scan.nextLine();
        args[0] = choice;
        
        switch (choice) {
            case "-u":
                System.out.println("How are files being uploaded on the client? File -f or network -n");
                args[1] = scan.nextLine();
                break;
            default:
                args[1] = "";
                break;
        }
        
        return args;
    }

    private void upload(String uploadType) {
        System.out.println("Loading the whole index into memory...");
        index = new Index();
        index.prepareWholeIndex();
        System.out.println();
        
        RetrieveUploadedFiles retriever = new RetrieveUploadedFiles(index);
        retriever.retrieve(uploadType);
        
        //Now write this all back to the file
        System.out.println("Writing the doc sizes back to file...");
        index.writeDocSizesToFile();
        System.out.println("Now writing the index back to file...");
        index.writePostingListToIndexFile();
        System.out.println("Done writing!");
    }

    private void search() {
        System.out.println("Loading a blank index into memory.  The clusters will be added at run time.");
        index = new Index();
        
        
        //Search indefinitely 
        CloudSearcher searcher = new CloudSearcher(index);
        
        //do {
            System.out.println("\nNow Awaiting Shard Picks... ");
            searcher.ReceiveClusterNames();
            System.out.println("\nNow awaiting Search...");
            searcher.ReceiveQuery();
            //System.out.println(searcher.rankRelatedFiles());
            searcher.rankRelatedFiles();
            searcher.sendResultsToClient();
        //} while (true);
    }

    private void partition() {
        System.out.println("Loading the whole index into memory...");
        index = new Index(); //wu
        index.prepareWholeIndex(); //wu
        System.out.println(); 
        
        System.out.println("Starting partition attempt...");
        //Create a partitioner object, pass it the index
        Partitioner part = new Partitioner(index);
        
        //Set how many clusters we'll create
        
        
        
        // part.setNumberOfClusters(Config.k);
        //zobaed's method
        
       // part.setNumberOfClusters(index.getNumberOfCluster());
        int a;
        //a= 187; //at first, we will comment out this line. then run the code. THe result it will give, then we put the value here and comment out the next line.
        //this will reduce run time.
        a= index.getNumberOfCluster();
        System.out.println("NUMBER OF CLUSTER: "+ a);
        part.setNumberOfClusters(a);
        
        
        
        //Create the clusters
        part.partition(); // this is not obviously same class partition (). Rather it comes from partitioner class.
        //Create the abstracts
        part.createAbstractIndices();
        
        System.out.println();
      //We need to write the clusters to files so we remember them
        part.writeClustersToFile();
        //Send said abstract indices to the client
        part.sendAbstractIndicesToClient();
        
        System.out.println();
        
        //Now the server has the clusters and the client has the abstracts
        
        
    }
    
    private void sendAbstracts() {
        System.out.println("Loading the whole index into memory");
        index = new Index();
        index.prepareWholeIndex();
        
        System.out.println("Getting the abstracts from the clusters...");
        
        Partitioner part = new Partitioner(index);
        //part.setNumberOfClusters(165); // index.getNumberOf 
        part.setNumberOfClusters(index.getNumberOfCluster());
        part.createAbstractIndicesFromIndex();
        part.sendAbstractIndicesToClient();
    }
}
