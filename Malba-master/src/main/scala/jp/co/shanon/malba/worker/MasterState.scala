package jp.co.shanon.malba.worker
import jp.co.shanon.malba.queue.CustomQueue
import scala.collection.immutable.HashMap
import jp.co.shanon.malba.queue.FIFOQueue

object MasterState {
  def empty: MasterState = MasterState(HashMap.empty[String,(String,Map[String, String])], HashMap.empty[String, CustomQueue])

  trait MasterDomainEvent
  case class TaskAdded(id: String, from: String, taskId: String, group: Option[String], option: Map[String, String], taskType: String, task: String) extends MasterDomainEvent
  case class TaskCanceledById(id: String, from: String, taskType: String, taskId: String) extends MasterDomainEvent
  case class TaskCanceledByGroup(id: String, from: String, taskType: String, group: String) extends MasterDomainEvent
  case class TaskSent(id: String, from: String, taskType: String) extends MasterDomainEvent
  case class TaskTypeSettingAdded(from: String, taskType: String, queueType: String, maxNrOfWorkers: Int, config: Map[String, String]) extends MasterDomainEvent
}


case class MasterState(
  taskTypeSetting: HashMap[String, ( String, Map[String, String] )],
  tasks: HashMap[String, CustomQueue]
) {
  import MasterState._

  def setTaskTypeSetting( taskType: String, queueType: String, maxNrOfWorkers: Int, config: Map[String, String] ): MasterState = {
    copy(taskTypeSetting = taskTypeSetting + ( taskType -> Tuple2(queueType, config) ))
  }

  def getInitialQueue( taskType: String ): CustomQueue = {
    val (queueName, config) = taskTypeSetting.getOrElse( taskType, Tuple2("jp.co.shanon.malba.queue.FIFOQueue", Map.empty[String, String]) )
    try {
      Class.forName(queueName).getConstructor(classOf[Map[String, String]]).newInstance( config ).asInstanceOf[CustomQueue]
    } catch {
      case e: Exception => 
        println( e.getMessage )
        new FIFOQueue(Map.empty[String, String])
    }
  }

  def nonEmpty(taskType: String): Boolean = {
    tasks.isDefinedAt(taskType)
  }

  def contains(taskType: String, taskId: String): Boolean = {
    nonEmpty(taskType) && tasks.apply(taskType).contains(taskId)
  }

  def enqueue( taskId: String, taskType: String, content: String, group: Option[String], option: Map[String, String] ): MasterState = {
    val task = Task( taskId, taskType, content )
    if( nonEmpty( taskType ) ){
      tasks.apply(taskType).enqueue( task, group, option )
      this
    } else {
      val taskList = getInitialQueue( taskType )
      taskList.enqueue( task, group, option )
      val newTasks = tasks + ( taskType -> taskList )
      copy(tasks = newTasks)
    }
  }

  def dequeue(taskType: String): (Option[Task], MasterState) = {
    val taskList = tasks.getOrElse(taskType, getInitialQueue( taskType ))
    if(taskList.isEmpty){
      (None, this)
    } else {
      val task = taskList.dequeue()
      if(taskList.isEmpty){
        (Some(task), copy( tasks =  tasks - taskType))
      } else {
        (Some(task), this)
      }
    }
  }

  def deleteById( taskType: String, id: String ): MasterState = {
    val taskList = tasks.getOrElse(taskType, getInitialQueue( taskType ))
    taskList.deleteById( id )
    if( taskList.isEmpty ){
      copy( tasks =  tasks - taskType)
    } else {
      this
    }
  }

  def deleteByGroup( taskType: String, group: String ): MasterState = {
    val taskList = tasks.getOrElse(taskType, getInitialQueue( taskType ))
    taskList.deleteByGroup( group )
    if( taskList.isEmpty ){
      copy( tasks =  tasks - taskType)
    } else {
      this
    }
  }

  def updated(event: MasterDomainEvent): MasterState = {
    event match {
      case TaskAdded(id, from, taskId, group, option, taskType, task) => 
        enqueue( taskId, taskType, task, group, option)
      case TaskCanceledById(id, from, taskType, taskId) => 
        deleteById( taskType, taskId )
      case TaskCanceledByGroup(id, from, taskType, group) => 
        deleteByGroup( taskType, group )
      case TaskSent(id, from, taskType) => 
        val ( task, state ) = dequeue( taskType )
        state
      case TaskTypeSettingAdded(from, taskType, queueType, maxNrOfWorkers, config) => 
        setTaskTypeSetting( taskType, queueType, maxNrOfWorkers, config )
    }
  }
}
