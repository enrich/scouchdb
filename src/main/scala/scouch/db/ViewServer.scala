package scouch.db

import javax.script.ScriptException;

import scala.collection.mutable.ArrayBuffer
import dispatch.json._
import dispatch.json.Js._
import sjson.json._

import java.io._

/** Result holder class used in interpreter */
class ResHolder(var value : Any)

/** The guts of the view server that handles various callbacks.
    The <tt>PrintWriter</tt> passed is used to log debug messages */
class ViewServer(val ps: PrintWriter) {
  
  /** stores the collection of map/reduce functions */
  var fns = new ArrayBuffer[(JsValue)=> Iterable[List[Any]]]
  var ddocs = new collection.mutable.HashMap[String, JsValue]
  
  import scala.tools.nsc._
  
  val s = new Settings
  s.classpath.value_=(System.getProperty("CDB_VIEW_CLASSPATH"))
  s.usejavacp.value = true

  /** The passed in <tt>PrintWriter</tt> is also used to log any message
      that the Scala interpreter spits out */
  val interpreter = new scala.tools.nsc.interpreter.IMain(s, ps) {
    override protected def parentClassLoader = this.getClass.getClassLoader 
  } 
  
  /** Evaluates the code snippet passed in. The snippet has to return a value */
  private def eval(code : String) : Any = {

    val holder = Array[Any](0)
    val r = interpreter.bind("$res__", "Array[Any]", holder)
    ps.println("bind res = " + r)
    val res = interpreter.addImports("dispatch.json._", "dispatch.json.Js._")
    ps.println("import res = " + res)

    // Execute the code and catch the result
    val ir = interpreter.interpret("$res__(0) = " + code);
    ps.println("interpret res = " + ir)
    
    import scala.tools.nsc.interpreter.Results._

    // Return value or throw an exception based on result
    ir match {
      case Success => holder(0)
      case Error => throw new ScriptException("error in: '" + code)
      case Incomplete => throw new ScriptException("incomplete in :'" + code)
    }
  }

  /** callback for handling reset */
  def reset = {
    ps.println("in reset")
    ps.flush
    fns.clear
    System.gc
    true
  }
  
  /** callback for handling log */
  def log(message: String) = {
    ps.println("in log:" + message)
    ps.flush
    val m = Map("log" -> message)
    JsValue.toJson(JsValue(m))
  }
  
  /** callback for handling add_fun. In case the function can be evaluated successfully,
      it returns <tt>true</tt> as <tt>Left[true]</tt>. Otherwise it returns the json
      representation of the error as the <tt>Right</tt> projection */
  def add_fun(fnStr: String): Either[Boolean, JsValue] = {
    ps.println("in add_fun:" + fnStr)
    ps.flush
    try {
      fns += eval(fnStr).asInstanceOf[Function1[JsValue, Iterable[List[Any]]]]
      Left(true)
    } catch {
      case se: ScriptException => {
        se.printStackTrace(ps)
        Right(JsValue(Map("error" -> "map_compilation_error", "reason" -> se.getMessage)))
      }
    }
  }

  /** callback for <tt>map_doc</tt> */
  def map_doc(docJs: JsValue) = {
    ps.println("in map_doc:" + docJs)
    ps.flush
    val res = 
      try {
        fns.foldLeft(List[Iterable[List[Any]]]())((s, f) => f(docJs) :: s)
      } catch {
        case e: Exception =>
          e.printStackTrace(ps)
          Nil
      }
    try {
      import sjson.json.Implicits._
      JsBean.toJSON(res.reverse)
    } catch {
      case e: Exception =>
        e.printStackTrace(ps)
        ps.flush
        throw(e)
    }
  }
  
  def reduce(rfuns: List[String], kids: List[(Any /** key */, String /** id */)], values: List[Any /** value */]) = {
    ps.println("in reduce:" + rfuns)
    ps.println("kids:" + kids + " size:" + kids.size)
    ps.println("values:" + values + " size:" + values.size)
    ps.flush
    
    val fs = rfuns.map(eval(_).asInstanceOf[Function3[List[(Any, String)], List[Any], Boolean, Any]])
    val rets = 
      try {
        fs.map(f => f(kids, values, false))
      } catch {
        case e: Exception =>
          e.printStackTrace(ps)
      }

    JsValue.toJson(JsValue(List(true, rets)))
  }

  def add_ddocs(ddocId: String, ddoc: JsValue) = ddocs += ((ddocId, ddoc))

  import ViewServerUtils._
  def validate(ddocname: String, funPath: String, doc: JsValue, args: JsValue): Either[String, JsValue] = {

    try {
      val ddoc = ddocs.get(ddocname).getOrElse(sys.error("query protocol error: uncached design doc: " + ddocname))
      val valid = 'validate_doc_update ? str
      val valid(valid_) = ddoc
      val fn = eval(valid_).asInstanceOf[Function3[JsValue, JsValue, Any, Any]]
      val f = fn(doc, doc, args)
      Left(JsValue.toJson(JsNumber(1)))
    } catch {
      case se: ScriptException =>
        se.printStackTrace(ps)
        ps.flush
        Right(JsValue(Map("error" -> "validation_compilation_error", "reason" -> se.getMessage)))
      case vx: ValidationException =>
        vx.printStackTrace(ps)
        ps.flush
        Right(JsValue(Map("forbidden" -> vx.getMessage)))
      case ux: AuthorizationException =>
        ux.printStackTrace(ps)
        ps.flush
        Right(JsValue(Map("unauthorized" -> ux.getMessage)))
      case x: Exception =>
        x.printStackTrace(ps)
        ps.flush
        Right(JsValue(Map("dummy" -> x.getMessage)))
    }
  }
}

/** command line interface of the view server that interacts with couchdb. This has to be
    entered in local.ini as the setting entry for the view server */
import sjson.json.Util._
object VS {
  
  def main(args: Array[String]) {
    val v = 
      if (args.size < 1) new ViewServer(new PrintWriter(System.err))
      else new ViewServer(new PrintWriter(args(0)))
    
    v.log("starting view server")

    val isr = new BufferedReader(new InputStreamReader(System.in))
    val p = new PrintWriter(System.out)
    
    var s = isr.readLine
    v.ps.println("**")
    v.ps.println(s)
    v.ps.println("**")
    v.ps.flush
    while (s != null) {
      Js(s) match {

        // handle reset: reset has changed
        // ["reset",{"reduce_limit":true}]
        case JsArray(JsString("reset") :: _) => {
          p.write(JsValue.toJson(JsValue(v.reset)))
          p.write('\n')
          p.flush
        }

        // handle add_fun
        case JsArray(List(JsString("add_fun"), JsString(fn))) => {
          v.add_fun(fn) match {
            case Left(true) => {
              p.write(JsValue.toJson(JsValue(true)))
              p.write('\n')
              p.flush
            }
            case Right(x) => {
              p.write(JsValue.toJson(x))
              p.write('\n')
              p.flush
            }
          }
        }
        
        // handle map_doc
        case JsArray(List(JsString("map_doc"), doc)) => {
          p.write(v.map_doc(doc))
          p.write('\n')
          p.flush
        }
        
        // handle reduce
        
        /**
         * protocol for reduce is complicated. Couchdb sends a list of reduce functions and some map results on which
         * to apply them.
         * 
         * e.g. CouchDB sends:
         * 
         * ["reduce", 
         *  ["function(k, v { return sum(v); }", "function(k, v { return mul(v); }"], 
         *  [[[1, "699b52"],10], [[2, "c081d0f"], 20], [[null, "foobar"], 3]]]
         * 
         * View Server returns:
         * 
         * [true, [33]]
         */
        case JsArray(List(JsString("reduce"), JsArray(rfns) /** list of reduce fns */, JsArray(x)) /** map data */) => {
          val reduceFns =
            rfns.map{r =>
              (r: @unchecked) match {
                case JsString(f) => f
              }
            }
          
          /**
           * current impl only for rereduce = false.
           * converts the map data to the tuple2 (List[Any, String], List[Any])
           */
          val (kids, vs) = 
            x.foldLeft((List[(Any, String)](), List[Any]())) { (s, f) =>
              (f: @unchecked) match {
                case JsArray(List(JsArray(List(JsString(key), JsString(id))), v)) => {
                  ((key, id) :: s._1, v :: s._2)
                }
              }
            }
          p.write(v.reduce(reduceFns, kids, vs))
          p.write('\n')
          p.flush
        }

        // handle validate

        /**
         * The protocol is 
         * CouchDB sends: 
         * 
         * ["validate", function string, new document, old document, request]
         *
         * View Server returns: 
         *
         * 1 if successful, otherwise exception having "error" -> "forbidden", "reason" -> anything
         *
         * The key "forbidden" is important - otherwise CouchDB will not send back 403.
         *
         * References:
         *  $COUCH_SOURCE/share/server/validate.js
         *  $COUCH_SOURCE/share/server/loop.js
         *  $COUCH_SOURCE/share/server/util.js
         *  $COUCH_HOME/test/query_server_spec.rb
         */
        case JsArray(List(JsString("ddoc"), JsString("new"), JsString(ddocname), doc)) => {
          v.add_ddocs(ddocname, doc)
          p.write(JsValue.toJson(JsValue(true)))
          p.write('\n')
          p.flush
        }

        case JsArray(List(JsString("ddoc"), JsString(ddocname), JsArray(JsString(fun) :: _), JsArray(doc :: _ :: args :: _))) => {
          v.validate(ddocname, fun, doc, args) match {
            case Left(s) => {
              p.write(s)
              p.write('\n')
              p.flush
            }
            case Right(x) => {
              p.write(JsValue.toJson(x))
              p.write('\n')
              p.flush
            }
          }
        }

        case _ =>
          p.write("[\"invalid input\"]")
          p.write("s = " + s)
          p.write("***********************************")
          p.flush
          v.ps.close
      }
      s = isr.readLine
    }
  }
}

/** Scala object for testing view server callbacks */
import java.io._


object ViewServerMain {
  
  def main(args: Array[String]) {
    val v = new ViewServer(new PrintWriter("/tmp/vs.txt"))
    val p = new java.io.PrintWriter(System.out)
    val isr = new InputStreamReader(System.in)
    val fr = new PrintWriter("dg.txt")
    while (true) {
      Js(readTillNl(isr)) match {
        
        // handle reset
        case JsArray(List(JsString("reset"))) => {
          print(JsValue.toJson(JsValue(v.reset)))
          print("\n")
        }
        
        // handle add_fun
        case JsArray(List(JsString("add_fun"), JsString(fn))) => {
          v.add_fun(fn) match {
            case Left(true) => {
              print(JsValue.toJson(JsValue(true)))
              print("\n")
            }
            case Right(x) => {
              print(JsValue.toJson(x))
              print("\n")
            }
          }
        }
        
        // handle map_doc
        case JsArray(List(JsString("map_doc"), doc)) => {
          print(v.map_doc(doc))
          print("\n")
        }
        
        case _ =>
          print("error\n")
          v.ps.close
      }
    }
  }
}

