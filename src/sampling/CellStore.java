package sampling;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class CellStore{
	public float x_1;
	public float x_2;
	public float y_1;
	public float y_2;
	public int core_partition_id = Integer.MIN_VALUE;
	public Set<Integer> support_partition_id = new HashSet<Integer>();
	public CellStore(float x_1,float x_2, float y_1,float y_2){
		this.x_1 = x_1;
		this.x_2 = x_2;
		this.y_1 = y_1;
		this.y_2 = y_2;
	}
	public String printCellStore(){
		String str = "";
		str += x_1 +","+x_2 + ","+y_1+"," + y_2+",";
		str += "core_partition:" + core_partition_id+","+ "support_partitions:";
		return str.substring(0,str.length()-1);
	}
	public String printCellStoreWithSupport(){
		String str = "";
		str += x_1 +","+x_2 + ","+y_1+"," + y_2+",";
		str += "C:" + core_partition_id+","+ "S:";
		for(Iterator itr = support_partition_id.iterator(); itr.hasNext();){ 
			int keyiter = (Integer) itr.next();
			str += keyiter+",";
		}
		return str.substring(0,str.length()-1);
	}
}



