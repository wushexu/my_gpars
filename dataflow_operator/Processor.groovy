package xy.pars

import groovyx.gpars.*
import groovyx.gpars.group.*
import groovyx.gpars.dataflow.*
import groovyx.gpars.dataflow.operator.*

import xy.util.*
import xy.pars.message.*

abstract class Processor {
	
    def config
    
    /**任务队列最大任务数*/
    static final MAX_QUEUE_LENGTH=50
    
    /**名字（不必含类型信息）*/
    String name
    
    int seq=1
    
    /**并行组：Pooled Parallel Group*/
    PGroup group
    
    /**任务（数据流）队列：当对列中有新的任务时，{@link #dp} 将会对之进行处理*/
    DataflowQueue tasksQueue=new DataflowQueue()
    
    /**任务（数据流）处理器：DataflowOperator*/
    DataflowProcessor dp
    
    /**前置处理器*/
    protected List<Processor> previous=[]
    
    /**后继处理器 choice successors*/
    protected List<Processor> successors=[]
    
	//    /**fork successors*/
	//    protected List<Processor> forkSuccessors=[]
    
    /**后继（异常）处理器*/
    protected List<ExceptionProcessor> exceptionProcessors=[]
    
    /**已接受记录计数器*/
    long acceptCounter=0
    
    /**已过滤记录计数器*/
    long filterOutCounter=0
    
    /**已出错记录计数器*/
    long errorCounter=0
    
    /**记录总计数器*/
    long processCounter=0
    
    /**是否在处理器链中作为打头的处理器 <br/>另见{@link Processor#start()}*/
    boolean starter=false
	
    protected Set<Processor> previousInRunning=[]
    
    protected int successorIndex=0
	protected int successorsSize
    protected Processor onlySuccessor
    
    List tasks
    
    protected int maxTasksCount=20
    
    /**决定后继处理器的闭包*/
    def successorDecide={ task ->
        if(successorsSize>1){
            def successor=successors[successorIndex]
            successorIndex++
            if(successorIndex>=successorsSize){
                successorIndex=0
            }
            return successor
        }
    }
    
    /**决定后继异常处理器的闭包*/
    def exceptionProcessorDecide={ task ->
        if(exceptionProcessors.size()>0){
            exceptionProcessors[0]
        }
    }
    
    /**初始化之前的工作*/
    protected beforeInit(){
    }
    
    /**初始化本处理器*/
    def init() {
        if(dp){
            return
        }
        println "init: $this >>> $successors"
		use([DateCategory,CalendarCategory,ExceptionCategory]){
			beforeInit()
		}
		successorsSize=successors.size()
		if(successorsSize==1){
			onlySuccessor=successors[0]
		}
        tasks=new ArrayList(maxTasksCount)
        
        previousInRunning=previous as Set
        def successorQueues=successors*.tasksQueue
        def code1= { task ->
            boolean success=true
            Result result
            try{
                result=process(task)
                if(result!=null){
                    if(result==Result.FILTER_OUT){
                        filterOutCounter++
						recycleTask(task)
                        return
                    }
                    if(result instanceof ExceptionResult){
                        success=false
                    }
                }
            }catch(e){
                use(ExceptionCategory){
                    e.printStackTrace2()
                }
                //result=new ExceptionResult(exception:e)
                success=false
            }finally{
				processCounter++
//				if(processCounter%10000==0){
//					println "${new Date().format('HH:mm:ss')} $this $processCounter"
//				}
			}
            if(success){
                def successor=onlySuccessor ?: successorDecide(task)
                if(!successor){
                    if(successorsSize>0){
                        filterOutCounter++
                    }else{
                        acceptCounter++
                    }
					recycleTask(task)
                    return
                }
                acceptCounter++
                def successorTasksQueue=successor.tasksQueue
                while(successorTasksQueue.length() >= MAX_QUEUE_LENGTH){
					Thread.yield()
                }
                if(onlySuccessor){
                    tasks << task
                    if(tasks.size()>=maxTasksCount){
                        successorTasksQueue << tasks
                        tasks=new ArrayList(maxTasksCount)
                    }
                }else{
                    successorTasksQueue << task
                }
                return
            }
            errorCounter++
            if(exceptionProcessors==null){
                return
            }
            def exceptionProcessor=exceptionProcessorDecide(task)
            if(exceptionProcessor){
                while(exceptionProcessor.tasksQueue.length() >= MAX_QUEUE_LENGTH){
                    Thread.yield()
                }
                exceptionProcessor.tasksQueue << task
            }
        }
        def coden={ any ->
			use([DateCategory,CalendarCategory,ExceptionCategory]){
				try{
					if(any instanceof List){
						any.each {
							try{
								code1(it)
							}catch(e){
								e.printStackTrace2()
							}
						}
					}else if(any instanceof Message){
						def message=any
						if(message instanceof Completed){
							previousInRunning.remove(message.sender)
							if(!previousInRunning.empty){
								return
							}
							try{
								if(onlySuccessor && tasks.size()>0){
									onlySuccessor.tasksQueue << tasks
									tasks=[]
								}
								def completedMessage=new Completed(sender:this)
								successors.each { it << completedMessage }
								exceptionProcessors.each { it << completedMessage }
								onCompleted()
							}catch(e){
								e.printStackTrace2()
							}
							dp.stop()
						}
					}else {
						code1(any)
					}
				}catch(e){
                    e.printStackTrace2()
                }
            }
        }
        dp=new DataflowOperator(group, [inputs: [tasksQueue], outputs: [successorQueues]], coden)
        
		use([DateCategory,CalendarCategory,ExceptionCategory]){
			afterInit()
		}
    }
    
    /**初始化之后的工作*/
    protected afterInit(){
    }
    
    /**发消息/任务*/
    def leftShift(v){
        tasksQueue << v
    }
	
    def rightShift(Processor successor){
		if(successor instanceof ExceptionProcessor){
			addExceptionProcessor(successor)
		}else{
			addSuccessor(successor)
		}
		return successor
	}
    
    /**  >> [...]，增加处理器*/
    def rightShift(List successors){
        if(!successors){
            return
        }
        successors.each {
            if(it instanceof ExceptionProcessor){
                addExceptionProcessor(it)
            }else{
                addSuccessor(it)
            }
        }
    }
    
    /**添加一个前置处理器*/
    def addSuccessor(successor){
        successors << successor
        successor.previous << this
    }
    
    /**添加一个后继处理器*/
    def addSuccessors(successors){
        successors.each {
            addSuccessor(it)
        }
    }
    
    /**添加一个异常处理器*/
    def addExceptionProcessor(exceptionProcessor){
        exceptionProcessor << exceptionProcessor
        exceptionProcessor.previous << this
    }
    
    /**添加多个个异常处理器*/
    def addExceptionProcessors(exceptionProcessors){
        exceptionProcessors.each {
            addExceptionProcessor(it)
        }
    }
    
    /**事件：处理完成*/
    def onCompleted(){
		def msg=new StringBuffer("${new Date().format('HH:mm:ss')} ...completed: ")
		msg << toString()
		msg << ", accept: ${acceptCounter}"
		if(errorCounter>0){
			msg << ", error: ${errorCounter}"
		}
		if(filterOutCounter>0){
			msg << ", filter: ${filterOutCounter}"
		}
        println msg
    }
	
    def start(){
        dp.start()
    }
    
    String toString(){
        "(${this.class.simpleName}: ${name}${seq>1 ? '_'+seq:''})"
    }
	
	protected void recycleTask(task){
		
	}
	
    /**接受并处理任务，返回处理情况；唯一必须实现的方法。*/
    abstract Result process(Task task);
    
}
