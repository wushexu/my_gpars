package xy.z.scene

import groovyx.gpars.*
import groovyx.gpars.actor.*
import groovyx.gpars.group.*
import groovyx.gpars.dataflow.*

import xy.acc.*
import xy.bas.*
import xy.mod.*
import xy.util.*
import xy.data.mcf.*
import xy.data.scene.*
import xy.data.dat.NewVolume
import xy.cfcal.CalHelper
import xy.cfcal.ocf.OcfCalculator
import xy.z.scene.NewVolumeBuilder
import xy.z.*
import static xy.z.Helper.*

/**
 * 目标余额情景模拟
 */
class WorkerSt extends Worker {
	
	protected List<MonthDFVs> monthDFVsList
	
	def init(){
		
		def goal=scene.goal
		if(goal){
			//1：新业务量；2：目标余额；3：增长比例
			if(goal.goalType==1){
				throw new RuntimeException("业务目标类型不是余额目标")
			}
		}
		
		super.init()
	}

	private void setupMonthDFVsList(){
	
		monthDFVsList=[]
		def lastMd=null
		cfDates.eachWithIndex { monthEnd,i ->
			def md=new MonthDFVs(monthIndex:i+1,monthEnd:monthEnd)
			if(lastMd){
				lastMd.next=md
				md.previous=lastMd
			}
			monthDFVsList << md
			lastMd=md
		}
	}

	private void driveByStockMcfs(DataflowVariable baseDateMcfsDFV){
	
		//加载存量业务
		DataflowVariable stockMcfsLoaderDFV=stockMcfsLoader()
		
		stockMcfsLoaderDFV.whenBound wrapWhenBound { List<Mcfd> allMcfs ->
			Map mcfsByCfDate=allMcfs.groupBy {it.cfDate}
			monthDFVsList.each { monthDFVs ->
				def mcfs=mcfsByCfDate[monthDFVs.monthEnd]
				monthDFVs.stockMcfsDFV.bind mcfs
			}
			def baseDateMcfs=[]
			def fdt=cfDates[0].time
			mcfsByCfDate.each{ cfDate,mcfs->
				if(cfDate.time < fdt){
					baseDateMcfs.addAll mcfs
				}
			}
			baseDateMcfsDFV.bind baseDateMcfs
		}
	}

	//计算新业务量
	private buildNewVolumes(MonthDFVs monthDFVs,DataflowVariable nvBaseMcfsDFV,
		DataflowVariable rateDispositionMapDFV,Actor ocfGeneratorActor){
		
		runTask {
			List nvBaseMcfs=nvBaseMcfsDFV.get()
			Map<Long,Double> accitemIdBalanceMap=Mcfs.buildItemBalanceMap(nvBaseMcfs)
			Map<AccItem,List<NewVolume>> newVolumes=NewVolumeBuilder.buildNewVolumesBasicByTargetBalance(scene,
				accitemIdBalanceMap,monthDFVs.monthIndex,monthDFVs.monthEnd)
			Map rateDispositionMap=rateDispositionMapDFV.get()
			newVolumes=NewVolumeBuilder.splitNewVolumeToTerms(scene,newVolumes,rateDispositionMap)
			buildAndDispatchTasks(ocfGeneratorActor,newVolumes,rateDispositionMap)
		}
	}

	private collectrFinalMcfs(MonthDFVs monthDFVs,DataflowVariable nvBaseMcfsDFV,
		DataflowVariable nvMcfsDFV,Map<Date,List> previousMcfsMap){
		
		runTask {
			List nvMcfs=nvMcfsDFV.get()
			Map<Date,List> mcfsMap=nvMcfs.groupBy{it.cfDate}
			List thisMonthMcfs=mcfsMap.remove(monthDFVs.monthEnd)
			def next=monthDFVs.next
			if(next){
				mcfsMap.each{ cfDate,mcfs ->
					def tmm=previousMcfsMap[cfDate]
					tmm.addAll mcfs
				}
				next.previousGeneratedMcfsMapDFV.bind previousMcfsMap
			}
			
			Actor collectorFromMcfActor=setupCollectorFromMcfActor(monthDFVs.finalMcfsDFV)
			
			List nvBaseMcfs=nvBaseMcfsDFV.get()
			nvBaseMcfs.each{
				collectorFromMcfActor << it
			}
			thisMonthMcfs.each{
				collectorFromMcfActor << it
			}
			collectorFromMcfActor << null
		}
	}

	private DataflowVariable storeResult(DataflowVariable baseDateMcfsDFV,
		DataflowVariable erateDispositionDFV,DataflowVariable durationsDFV){
		
		runTask {->
			DataflowVariable storeInitialized=storeInitialize()
			storeInitialized.join()
			
			def erateDisposition=erateDispositionDFV.get()
			
			def allFinalNewVolumes=[]
			def allFinalMcfs=baseDateMcfsDFV.get()
			monthDFVsList.each { monthDFVs ->
				def finalNewVolumes=monthDFVs.finalNewVolumesDFV.get()
				allFinalNewVolumes.addAll finalNewVolumes
				def finalMcfs=monthDFVs.finalMcfsDFV.get()
				allFinalMcfs.addAll finalMcfs
			}
			//存储新业务量
			def spn=storePlanNvs(allFinalNewVolumes,erateDisposition)
			//存储结果月现金流
			def sfm=storeFinalMcfs(allFinalMcfs,erateDisposition)
			sfm.join()
			spn.join()
			
			def durations=durationsDFV.get()
			resultStore.storeDurations(durations)
		}
	}
	
	DataflowVariable start(){
		
		println "workerSt is starting..."
		
		setupMonthDFVsList()
		
		DataflowVariable baseDateMcfsDFV=new DataflowVariable()
		
		driveByStockMcfs(baseDateMcfsDFV)
		
		//利率模拟，accitemId -> termId -> rateDisposition
		DataflowVariable rateDispositionMapDFV=loadRateDispositionMap()
		
		//折现率，accitemId -> termId -> discountDisposition
		DataflowVariable discountDispositionMapDFV=loadDiscountDispositionMap()
		
		//计算市值与久期
		DataflowVariable durationsDFV=generateDurations(discountDispositionMapDFV)
		
		//汇率模拟
		DataflowVariable erateDispositionDFV=loadErateDisposition()
		
		monthDFVsList.each { monthDFVs ->
			
			runTask {
				
				//本月现金流，基于此计算本月新业务量
				DataflowVariable nvBaseMcfsDFV=new DataflowVariable()
				
				Actor nvBaseMcfsCollectorActor=setupCollectorFromMcfActor(nvBaseMcfsDFV,2)
				
				repriceStockMcfs(monthDFVs.stockMcfsDFV,nvBaseMcfsCollectorActor,rateDispositionMapDFV)
				
				Map<Date,List> previousMcfsMap
				if(monthDFVs.previous){
					previousMcfsMap=monthDFVs.previousGeneratedMcfsMapDFV.get()
				}else{
					previousMcfsMap=[:].withDefault{[]}
				}
				List previousMcfs=previousMcfsMap.remove(monthDFVs.monthEnd)
				previousMcfs.each{ mcf ->
					nvBaseMcfsCollectorActor << mcf
				}
				nvBaseMcfsCollectorActor << null
				
				
				//新业务量最终月现金流
				DataflowVariable nvMcfsDFV=new DataflowVariable()
				
				//汇总基础月现金流
				Actor nvMcfAggregatorActor=setupMcfAggregatorActor(nvMcfsDFV)
				
				//新业务量重定价
				Actor nvRepriceActor=setupNvRepriceActor(nvMcfAggregatorActor)
				
				//分重定价
				Actor repriceSplitterActor=setupRepriceSplitterActor(nvRepriceActor,monthDFVs.finalNewVolumesDFV)
				
				//生成基础月现金流
				Actor mcfGeneratorActor=setupMcfGeneratorActor(repriceSplitterActor)
				
				//生成新业务量发生现金流
				Actor ocfGeneratorActor=setupOcfGeneratorActor(mcfGeneratorActor)
				
				//计算新业务量
				buildNewVolumes(monthDFVs,nvBaseMcfsDFV,rateDispositionMapDFV,ocfGeneratorActor)
				
				//合并最终月现金流
				collectrFinalMcfs(monthDFVs,nvBaseMcfsDFV,nvMcfsDFV,previousMcfsMap)
			}
		}
		
		runTask {->
			def storeResultDFV=storeResult(baseDateMcfsDFV,erateDispositionDFV,durationsDFV)
			storeResultDFV.join()
			def sdtDFV=sumDataTable()
			sdtDFV.join()
			workerStatusDFV.bindSafely 'done'
		}
		
		return workerStatusDFV
	}
	
}


class MonthDFVs {
	
	int monthIndex
	
	Date monthEnd
	
	MonthDFVs previous
	
	MonthDFVs next
	
	//存量业务
	DataflowVariable stockMcfsDFV=new DataflowVariable()
	
	//新业务量
	DataflowVariable finalNewVolumesDFV=new DataflowVariable()
	
	//前面月份生成的现金流(Map<Date,List>)
	DataflowVariable previousGeneratedMcfsMapDFV=new DataflowVariable()
	
	//本月最终月现金流
	DataflowVariable finalMcfsDFV=new DataflowVariable()
	
}
