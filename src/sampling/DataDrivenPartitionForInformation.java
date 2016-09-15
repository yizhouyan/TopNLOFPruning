package sampling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.ToolRunner;

import metricspace.PairSorting;
import util.SQConfig;

/**
 * DDrivePartition class contains map and reduce functions for Data Driven
 * Partitioning.
 *
 * @author Yizhou Yan
 * @version Dec 31, 2015
 */
public class DataDrivenPartitionForInformation {
	/**
	 * default Map class.
	 *
	 * @author Yizhou Yan
	 * @version Dec 31, 2015
	 */

	public static class DDMapper extends Mapper<LongWritable, Text, IntWritable, Text> {

		/** incrementing index of divisions generated in mapper */
		public static int increIndex = 0;

		/** number of divisions where data is divided into (set by user) */
		private int denominator = 100;

		/** number of object pairs to be computed */
		static enum Counters {
			MapCount
		}

		protected void setup(Context context) throws IOException, InterruptedException {
			Configuration conf = context.getConfiguration();
			denominator = conf.getInt(SQConfig.strSamplingPercentage, 100);
		}

		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
			int id = increIndex % denominator;
			increIndex++;

			Text dat = new Text(value.toString());
			IntWritable key_id = new IntWritable(id);
			if (id == 0) {
				context.write(key_id, dat);
				context.getCounter(Counters.MapCount).increment(1);
			}
		}
	}

	/**
	 * @author yizhouyan
	 *
	 */

	public static class DDReducer extends Reducer<IntWritable, Text, NullWritable, Text> {
		private MultipleOutputs mos;
		/**
		 * The dimension of data (set by user, now only support dimension of 2,
		 * if change to 3 or more, has to change some codes)
		 */
		private static int num_dims = 2;
		/**
		 * number of small cells per dimension: when come with a node, map to a
		 * range (divide the domain into small_cell_num_per_dim) (set by user)
		 */
		public static int cell_num = 501;

		/** The domains. (set by user) */
		private static double[][] domains;

		/** size of each small buckets */
		private static int smallRange;

		/** save each small buckets. in order to speed up mapping process */
		private static CellStore[][] cell_store;
		/**
		 * Number of desired partitions in each dimension (set by user), for
		 * Data Driven partition
		 */
		private static int[] di_numBuckets;
		/**
		 * block list, which saves each block's info including start & end
		 * positions on each dimension. print for speed up "mapping"
		 */
		private static double[][] partition_store;
		/** the total usage of the block list (partition store) */
		private static int indexForPartitionStore = 0;
		/** in order to build hash to speed up mapping process */
		public static Hashtable<Double, StartAndEnd> start_end_points;

		private static int[] partition_size;

		// to compute chi square of each partition
		private static double[] chisquarePerPartition;
		private static int[] numCellsPerPartition;
		private ArrayList<PairSorting> priority;
		private static int numOfReducers;

//		double minChiSquare = Double.MAX_VALUE;
//		double maxChiSquare = Double.MIN_VALUE;
//		int minNumOfPoints = Integer.MAX_VALUE;
//		int maxNumOfPoints = Integer.MIN_VALUE;
//		int minNumOfCells = Integer.MAX_VALUE;
//		int maxNumOfCells = Integer.MIN_VALUE;

		public static class StartAndEnd {
			public int start_point;
			public int end_point;

			public StartAndEnd(int start_point, int end_point) {
				this.start_point = start_point;
				this.end_point = end_point;
			}
		}

		/**
		 * sort chi squares by dimension
		 * 
		 * @param chisquares
		 *            an array of chisquares
		 * @return a sorted dimension
		 */
		public int[] sortDim(double[] chisquares) {
			int len = chisquares.length;
			int[] sorted_dim = new int[len];
			for (int i = 0; i < len; i++) {
				sorted_dim[i] = i;
			}
			for (int i = 0; i < len; i++) {
				for (int j = i + 1; j < len; j++) {
					double temp;
					int tempindex;
					if (chisquares[i] > chisquares[j]) {
						temp = chisquares[j];
						chisquares[j] = chisquares[i];
						chisquares[i] = temp;
						tempindex = sorted_dim[j];
						sorted_dim[j] = sorted_dim[i];
						sorted_dim[i] = tempindex;
					}
				}
			}
			return sorted_dim;
		}

		public void setupIndexes() {
			double previous_dim_index = partition_store[0][0];
			int start_from = 0;
			int end_to = 0;
			for (int i = 0; i < indexForPartitionStore; i++) {
				if (partition_store[i][0] == previous_dim_index) {
					end_to = i;
				} else {
					start_end_points.put(previous_dim_index, new StartAndEnd(start_from, end_to));
					previous_dim_index = partition_store[i][0];
					start_from = i;
					end_to = i;
				}
			}
			start_end_points.put(previous_dim_index, new StartAndEnd(start_from, end_to));

			/*
			 * System.out.println("Indexes for blocklist"); for(Iterator itr =
			 * start_end_points.keySet().iterator(); itr.hasNext();){ Double
			 * keyiter = (Double) itr.next();
			 * System.out.println(keyiter+","+start_end_points.get(keyiter).
			 * start_point+"------"+start_end_points.get(keyiter).end_point); }
			 */
		}

		public int compareTwoNumbers(double[] aaa, double[] bbb) {
			if (aaa[0] > bbb[0])
				return 1;
			else if (aaa[0] < bbb[0])
				return -1;
			else {
				if (aaa[2] > bbb[2])
					return 1;
				else if (aaa[2] < bbb[2])
					return -1;
				else
					return 0;
			}
		}

		public void sortBlocklist() {
			int len = indexForPartitionStore;
			for (int i = 0; i < len; i++) {
				for (int j = i + 1; j < len; j++) {
					double[] temp = new double[num_dims * 2];
					if (compareTwoNumbers(partition_store[i], partition_store[j]) > 0) {
						temp = partition_store[j];
						partition_store[j] = partition_store[i];
						partition_store[i] = temp;

						int tempSize = partition_size[j];
						partition_size[j] = partition_size[i];
						partition_size[i] = tempSize;

						double tempChiSquare = chisquarePerPartition[j];
						chisquarePerPartition[j] = chisquarePerPartition[i];
						chisquarePerPartition[i] = tempChiSquare;

						int tempNumCells = numCellsPerPartition[j];
						numCellsPerPartition[j] = numCellsPerPartition[i];
						numCellsPerPartition[i] = tempNumCells;
					}
				}
			}
		}

		public int markEndPoints(double crd) {
			int start_end = indexForPartitionStore;
			double for_end = crd;
			for (Iterator itr = start_end_points.keySet().iterator(); itr.hasNext();) {
				Double keyiter = (Double) itr.next();
				if ((keyiter > for_end) && ((start_end_points.get(keyiter).start_point) < start_end))
					start_end = start_end_points.get(keyiter).start_point;
			}
			return start_end;
		}

		// find core partition according to the left downer node
		public int findAndSaveCorePartition(double x, double y) {
			for (int blk_id = 0; blk_id < markEndPoints(x); blk_id++) {
				if (x < partition_store[blk_id][1] && x >= partition_store[blk_id][0] && y >= partition_store[blk_id][2]
						&& y < partition_store[blk_id][3])
					return blk_id;
			}
			return Integer.MIN_VALUE;
		}

		public void dealEachSmallCell(int i, int j) {
			cell_store[i][j] = new CellStore(i * smallRange, ((i + 1) * smallRange), j * smallRange,
					((j + 1) * smallRange));
			int Core_partition_id = findAndSaveCorePartition(cell_store[i][j].x_1, cell_store[i][j].y_1);
			cell_store[i][j].core_partition_id = Core_partition_id;
		}

		public double computeChiSquareForEachPartition(PartitionPlan pp) {
			double chisquare = 0;
			int num_cells = 1;
			for (int i = 0; i < num_dims; i++) {
				int temp_num_cells = (int) (Math.ceil((pp.endDomain[i] - pp.startDomain[i]) / smallRange));
				num_cells *= temp_num_cells;
			}

			double avg_numData = pp.getNumData() * 1.0 / num_cells;
			// load data into frequency
			for (Iterator itr = pp.dataPoints.keySet().iterator(); itr.hasNext();) {
				String key = (String) itr.next();
				int value = (int) pp.dataPoints.get(key);
				double f = value - avg_numData;
				chisquare += f * f;
			}
			chisquare /= avg_numData;
			// chisquare /= num_cells;
			// double priority = Math.log(chisquare *
			// Math.sqrt(pp.getNumData()));
			// System.out.println("Chi-Square :" + chisquare + ", Num Of Points:
			// " + pp.getNumData() + ", Priority: " + priority);
			return chisquare;
		}

		public int getTotalCellsForEachPartition(PartitionPlan pp) {
			int num_cells = 1;
			for (int i = 0; i < num_dims; i++) {
				int temp_num_cells = (int) (Math.ceil((pp.endDomain[i] - pp.startDomain[i]) / smallRange));
				num_cells *= temp_num_cells;
			}
			return num_cells;
		}

		public void setup(Context context) {
			mos = new MultipleOutputs(context);

			/** get configuration from file */
			Configuration conf = context.getConfiguration();
			num_dims = conf.getInt(SQConfig.strDimExpression, 2);
			cell_num = conf.getInt(SQConfig.strNumOfSmallCells, 501);
			domains = new double[num_dims][2];
			domains[0][0] = domains[1][0] = conf.getDouble(SQConfig.strDomainMin, 0.0);
			domains[0][1] = domains[1][1] = conf.getDouble(SQConfig.strDomainMax, 10001);
			smallRange = (int) Math.ceil((domains[0][1] - domains[0][0]) / cell_num);
			cell_store = new CellStore[cell_num][cell_num];
			di_numBuckets = new int[num_dims];
			for (int i = 0; i < num_dims; i++) {
				di_numBuckets[i] = conf.getInt(SQConfig.strNumOfPartitions, 2);
			}
			partition_store = new double[di_numBuckets[0] * di_numBuckets[1] + 1][num_dims * 2];
			partition_size = new int[di_numBuckets[0] * di_numBuckets[1] + 1];
			start_end_points = new Hashtable<Double, StartAndEnd>();

			chisquarePerPartition = new double[di_numBuckets[0] * di_numBuckets[1] + 1];
			numCellsPerPartition = new int[di_numBuckets[0] * di_numBuckets[1] + 1];
			priority = new ArrayList<PairSorting>();
			numOfReducers = conf.getInt(SQConfig.strNumOfReducers, 100);
		}

		/**
		 * default Reduce class.
		 * 
		 * @author Yizhou Yan
		 * @version Dec 31, 2015
		 */

		public void reduce(IntWritable key, Iterable<Text> values, Context context) throws IOException {
			int num_data = 0; // number of data
			int[][] frequency = new int[num_dims][cell_num]; // frequency of
																// data in each
																// dimension,for
																// chi-square
			Hashtable<String, Integer> mapData = new Hashtable<String, Integer>(); // store
																					// data
																					// according
																					// to
																					// dimensions

			if (Integer.parseInt(key.toString()) == 0) {
				// collect data
				for (Text oneValue : values) {
					String line = oneValue.toString();
					// System.out.println("key: "+ key.toString()+ " value:
					// "+line);
					num_data++;
					String newLine = "";
					// rescale data and save to hashtable
					for (int i = 0; i < num_dims; i++) {
						double tempDataPerDim = Double.valueOf(line.split(",")[i + 1]);
						int indexDataPerDim = (int) (Math.floor(tempDataPerDim / smallRange));
						frequency[i][indexDataPerDim]++;
						newLine = newLine + indexDataPerDim * smallRange + ",";
					}
					newLine = newLine.substring(0, newLine.length() - 1);
					if (mapData.get(newLine) != null) {
						int tempFreq = mapData.get(newLine);
						mapData.put(newLine, tempFreq + 1);
					} else {
						mapData.put(newLine, 1);
					}
				} // end collection data
				double[] chisquares = new double[num_dims];

				// calculate chi-squares for each dimension
				for (int i = 0; i < num_dims; i++) {
					chisquares[i] = Cal_chisquare.Chi_square(frequency[i]);
				}

				// sort dimensions according to chi-squares
				int[] sorted_dim = sortDim(chisquares);

				// a queue to store partition plans
				Queue<PartitionPlan> queueOfPartition = new LinkedList<PartitionPlan>();

				// create object PartitionPlan and load all data into this plan
				double[] startOfRange = { domains[0][0], domains[1][0] };
				double[] endsOfRange = { domains[0][1], domains[1][1] };
				PartitionPlan pp = new PartitionPlan();
				pp.setupPartitionPlan(num_dims, num_data, di_numBuckets, startOfRange, endsOfRange, cell_num,
						smallRange);
				pp.addDataPoints(mapData);
				// pp.printPartitionPlan();

				System.out.println("----------------------------Start 1st Partition---------------------------------");
				// Create equi-depth partitions for 1st dimension in dimList
				PartitionPlan[] newPP = new PartitionPlan[di_numBuckets[sorted_dim[0]]];
				newPP = pp.seperatePartitions(sorted_dim[0], context.getConfiguration().getInt(SQConfig.strK, 3));
				for (int i = 0; i < newPP.length; i++) {
					queueOfPartition.offer(newPP[i]);
					// newPP[i].printPartitionPlan();
				}

				PartitionPlan[] PPs;
				// For each partition,
				System.out.println("------------------------------Start partition----------------------------");
				int multiplies = 1;
				for (int i = 1; i < num_dims; i++) {
					System.out.println(
							"------------------------------Start partition for " + (i + 1) + " th------------------");
					int newByDim = sorted_dim[i];
					multiplies *= di_numBuckets[sorted_dim[i - 1]];
					for (int j = 0; j < multiplies; j++) {
						// System.out.println("Dealing with ...." + j);
						PartitionPlan needPartition = queueOfPartition.poll();
						if (needPartition.getNumData() < context.getConfiguration().getInt(SQConfig.strK, 3)) { /////////////////////////////////
							if (i == num_dims - 1) {
								String outputStr = needPartition.getStartAndEnd();
								if (outputStr.length() > 3) {
									String[] sub_splits = outputStr.split(",");
									partition_store[indexForPartitionStore][0] = Double.valueOf(sub_splits[0]);
									partition_store[indexForPartitionStore][1] = Double.valueOf(sub_splits[1]);
									partition_store[indexForPartitionStore][2] = Double.valueOf(sub_splits[2]);
									partition_store[indexForPartitionStore][3] = Double.valueOf(sub_splits[3]);
									partition_size[indexForPartitionStore] = needPartition.getNumData();

									// compute chi-square for the partition
									chisquarePerPartition[indexForPartitionStore] = computeChiSquareForEachPartition(
											needPartition);
									numCellsPerPartition[indexForPartitionStore] = getTotalCellsForEachPartition(
											needPartition);

//									minChiSquare = Math.min(minChiSquare,
//											chisquarePerPartition[indexForPartitionStore]);
//									maxChiSquare = Math.max(maxChiSquare,
//											chisquarePerPartition[indexForPartitionStore]);
//									minNumOfPoints = Math.min(minNumOfPoints, partition_size[indexForPartitionStore]);
//									maxNumOfPoints = Math.max(maxNumOfPoints, partition_size[indexForPartitionStore]);
//									minNumOfCells = Math.min(minNumOfCells,
//											numCellsPerPartition[indexForPartitionStore]);
//									maxNumOfCells = Math.max(maxNumOfCells,
//											numCellsPerPartition[indexForPartitionStore]);

									indexForPartitionStore++;

									// output.collect(new Text(""), new
									// Text(outputStr));
								}
							}
						} else {
							PPs = new PartitionPlan[di_numBuckets[newByDim]];
							PPs = needPartition.seperatePartitions(newByDim,
									context.getConfiguration().getInt(SQConfig.strK, 3));

							for (int k = 0; k < PPs.length; k++) {
								if (i == num_dims - 1) {
									String outputStr = PPs[k].getStartAndEnd();
									if (outputStr.length() > 3) {
										// output.collect(new Text(""), new
										// Text(outputStr));
										String[] sub_splits = outputStr.split(",");
										partition_store[indexForPartitionStore][0] = Double.valueOf(sub_splits[0]);
										partition_store[indexForPartitionStore][1] = Double.valueOf(sub_splits[1]);
										partition_store[indexForPartitionStore][2] = Double.valueOf(sub_splits[2]);
										partition_store[indexForPartitionStore][3] = Double.valueOf(sub_splits[3]);
										partition_size[indexForPartitionStore] = PPs[k].getNumData();

										// compute chi-square for the partition
										chisquarePerPartition[indexForPartitionStore] = computeChiSquareForEachPartition(
												PPs[k]);
										numCellsPerPartition[indexForPartitionStore] = getTotalCellsForEachPartition(
												PPs[k]);

//										minChiSquare = Math.min(minChiSquare,
//												chisquarePerPartition[indexForPartitionStore]);
//										maxChiSquare = Math.max(maxChiSquare,
//												chisquarePerPartition[indexForPartitionStore]);
//										minNumOfPoints = Math.min(minNumOfPoints,
//												partition_size[indexForPartitionStore]);
//										maxNumOfPoints = Math.max(maxNumOfPoints,
//												partition_size[indexForPartitionStore]);
//										minNumOfCells = Math.min(minNumOfCells,
//												numCellsPerPartition[indexForPartitionStore]);
//										maxNumOfCells = Math.max(maxNumOfCells,
//												numCellsPerPartition[indexForPartitionStore]);

										indexForPartitionStore++;
									}
								}
								queueOfPartition.offer(PPs[k]);
							}
						}
					} // end for(int j = 0; j < multiplies; j++)
				}
			} // end if Integer.parseInt(key.toString()) == 0

			sortBlocklist();
			setupIndexes();

			String outputPPPath = context.getConfiguration().get(SQConfig.strPartitionPlanOutput);

			// compute priority and output partition plan
			for (int i = 0; i < indexForPartitionStore; i++) {

				double tempPriority = chisquarePerPartition[i] * numCellsPerPartition[i] / (partition_size[i]);
				priority.add(new PairSorting(i, tempPriority));
				try {
					mos.write(NullWritable.get(),
							new Text(i + "," + partition_store[i][0] + "," + partition_store[i][1] + ","
									+ partition_store[i][2] + "," + partition_store[i][3] + ","
									+ String.format("%.5f", tempPriority)),
							outputPPPath + "/pp");
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}

			// // sort priority and output partition assingment plan
			// TreeMap<Double, Integer> map = new TreeMap<Double,
			// Integer>(Collections.reverseOrder());
			// for (int i = 0; i < indexForPartitionStore; i++) {
			// map.put(priority[i], i);
			// }
			// for(double i : map.keySet()){
			// System.out.println("Value: " + i);
			// }
			Collections.sort(priority, new Comparator<PairSorting>() {
				@Override
				public int compare(PairSorting ps1, PairSorting ps2) {
					return -1 * Double.valueOf(ps1.value).compareTo(ps2.value);
				}
			});
			int count = 0;
			for (int i = 0; i < indexForPartitionStore; i++) {
				try {
					mos.write(NullWritable.get(), new Text(priority.get(i).index + "," + (count % numOfReducers)),
							outputPPPath + "/partitionAssignment");
					count++;
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				// System.out.println("Indices: " + priority.get(i).index + ",
				// Value: " + priority.get(i).value);
			}

			// String outputCellsPath =
			// context.getConfiguration().get(SQConfig.strCellsOutput);

			// output partition plan
			// for(int i = 0;i<indexForPartitionStore ;i++){
			//// double tempNumOfPoints =
			// (partition_size[i]-minNumOfPoints)*1.0/(maxNumOfPoints-
			// minNumOfPoints);
			//// double tempNumOfCells =
			// (numCellsPerPartition[i]-minNumOfCells)*1.0/(maxNumOfCells-minNumOfCells);
			//// double tempChiSquare = (chisquarePerPartition[i] -
			// minChiSquare)/(maxChiSquare-minChiSquare);
			////
			//// double priority = -0.6192909559 * tempNumOfCells + 1.177383519
			// * tempChiSquare + 5.53278202;
			//
			// try {
			// // chisquare*Range/log(#of points)
			//// mos.write(NullWritable.get(),new
			// Text(i+","+partition_store[i][0]+","+partition_store[i][1]
			//// +","+partition_store[i][2]+","+partition_store[i][3] + "," +
			//// String.format("%.5f", chisquarePerPartition[i] *
			// numCellsPerPartition[i] /Math.log(partition_size[i]) )),
			// outputPPPath+"/pp");
			////
			// // chisquare * Range / # of points
			//
			////
			// // chisquare * Range / # of points
			//// mos.write(NullWritable.get(),new
			// Text(i+","+partition_store[i][0]+","+partition_store[i][1]
			//// +","+partition_store[i][2]+","+partition_store[i][3] + "," +
			//// String.format("%.5f", tempChiSquare * tempNumOfCells
			// /tempNumOfPoints )), outputPPPath+"/pp");
			//
			//// mos.write(NullWritable.get(),new Text(i+"\t"+ tempNumOfPoints +
			// "\t" + tempNumOfCells
			//// +"\t" +
			//// tempChiSquare + "\t" + partition_size[i] + "\t" +
			// numCellsPerPartition[i] + "\t" +
			//// chisquarePerPartition[i]), "/lof/sampling/collectinfo/pp");
			// } catch (InterruptedException e) {
			// // TODO Auto-generated catch block
			// e.printStackTrace();
			// }
			// }

			// output cell based partition plan
			// for (int i = 0;i < cell_num;i++){
			// System.out.println("Dealing with cell: " + i);
			// for(int j = 0;j< cell_num;j++){
			// try {
			// dealEachSmallCell(i,j);
			// mos.write(new IntWritable(2),new
			// Text(cell_store[i][j].printCellStore()),
			// outputCellsPath+"/cells");
			// } catch (InterruptedException e) {
			// // TODO Auto-generated catch block
			// e.printStackTrace();
			// }
			// }
			// }

		} // end reduce function

		public void cleanup(Context context) throws IOException, InterruptedException {
			mos.close();
		}
	} // end reduce class

	public void run(String[] args) throws Exception {
		Configuration conf = new Configuration();
		conf.addResource(new Path("/usr/local/Cellar/hadoop/etc/hadoop/core-site.xml"));
		conf.addResource(new Path("/usr/local/Cellar/hadoop/etc/hadoop/hdfs-site.xml"));
		new GenericOptionsParser(conf, args).getRemainingArgs();
		/** set job parameter */
		Job job = Job.getInstance(conf, "Distributed Data Driven Sampling");

		job.setJarByClass(DataDrivenPartitionForInformation.class);
		job.setMapperClass(DDMapper.class);

		/** set multiple output path */
		MultipleOutputs.addNamedOutput(job, "partitionplan", TextOutputFormat.class, IntWritable.class, Text.class);
		// MultipleOutputs.addNamedOutput(job, "cells", TextOutputFormat.class,
		// IntWritable.class, Text.class);
		MultipleOutputs.addNamedOutput(job, "InfomationCollect", TextOutputFormat.class, IntWritable.class, Text.class);

		job.setReducerClass(DDReducer.class);
		job.setMapOutputKeyClass(IntWritable.class);
		job.setMapOutputValueClass(Text.class);
		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(Text.class);
		job.setNumReduceTasks(1);
		// System.out.println("Input Path:" + conf.get(SQConfig.dataset));
		String strFSName = conf.get("fs.default.name");
		FileInputFormat.addInputPath(job, new Path(conf.get(SQConfig.dataset)));
		FileSystem fs = FileSystem.get(conf);
		fs.delete(new Path(conf.get(SQConfig.strSamplingOutput)), true);
		FileOutputFormat.setOutputPath(job, new Path(conf.get(SQConfig.strSamplingOutput)));

		// job.addCacheFile(new URI(strFSName +
		// conf.get(SQConfig.strPivotInput)));
		// job.addCacheFile(new URI(strFSName +
		// conf.get(SQConfig.strMergeIndexOutput)
		// + Path.SEPARATOR + "summary" + SQConfig.strIndexExpression1));

		/** print job parameter */
		System.err.println("# of dim: " + conf.getInt(SQConfig.strDimExpression, 10));
		long begin = System.currentTimeMillis();
		job.waitForCompletion(true);
		long end = System.currentTimeMillis();
		long second = (end - begin) / 1000;
		System.err.println(job.getJobName() + " takes " + second + " seconds");
	}

	public static void main(String[] args) throws Exception {
		DataDrivenPartitionForInformation DDPartition = new DataDrivenPartitionForInformation();
		DDPartition.run(args);
	}

}
