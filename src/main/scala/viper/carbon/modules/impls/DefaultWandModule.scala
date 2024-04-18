// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2021 ETH Zurich.

package viper.carbon.modules.impls


import viper.carbon.modules._
import viper.carbon.verifier.Verifier
import viper.carbon.boogie._
import viper.carbon.boogie.Implicits._
import viper.carbon.modules.components.{DefinednessComponent, StmtComponent}
import viper.carbon.utility.FractionsDetector
import viper.silver.ast.utility.Expressions
import viper.silver.ast.{FullPerm, MagicWand, MagicWandStructure}
import viper.silver.cfg.silver.CfgGenerator.EmptyStmt
import viper.silver.verifier.{PartialVerificationError, VerificationError, reasons, errors}
import viper.silver.{ast => sil}

import scala.collection.mutable.ListBuffer

class
DefaultWandModule(val verifier: Verifier) extends WandModule with StmtComponent with DefinednessComponent{
  import verifier._
  import stateModule._
  import permModule._

  val transferNamespace = verifier.freshNamespace("transfer")
  val wandNamespace = verifier.freshNamespace("wands")
  //wands stored
  type WandShape = Func
  //This needs to be resettable, which is why "lazy val" is not used. See also: wandToShapes method
  private var lazyWandToShapes: Option[Map[MagicWandStructure.MagicWandStructure, WandShape]] = None





  /** CONSTANTS FOR TRANSFER START**/

  /* denotes amount of permission to add/remove during a specific transfer */
  val transferAmountLocal: LocalVar = LocalVar(Identifier("takeTransfer")(transferNamespace), permType)

  /*stores amount of permission still needed during transfer*/
  val neededLocal: LocalVar = LocalVar(Identifier("neededTransfer")(transferNamespace),permType)

  /*stores initial amount of permission needed during transfer*/
  val initNeededLocal = LocalVar(Identifier("initNeededTransfer")(transferNamespace), permType)

  /*used to store the current permission of the top state of the stack during transfer*/
  val curpermLocal = LocalVar(Identifier("maskTransfer")(transferNamespace), permType)

  /*denotes the receiver evaluated in the Used state, this is inserted as receiver to FieldAccessPredicates such that
  * existing interfaces can be reused and the transfer can be kept general */

  val rcvLocal: LocalVar = LocalVar(Identifier("rcvLocal")(transferNamespace), heapModule.refType)

  /* boolean variable which is used to accumulate assertions concerning the validity of a transfer*/
  val boolTransferTop = LocalVar(Identifier("accVar2")(transferNamespace), Bool)

  /** CONSTANTS FOR TRANSFER END**/

  /* Gaurav (03.04.15): this seems nicer as an end result in the Boogie program but null for pos,info params is
   * not really nice
   *   val boogieNoPerm:Exp = permModule.translatePerm(sil.NoPerm()(null,null))
  */
  val boogieNoPerm:Exp = RealLit(0)
  val boogieFullPerm:Exp = RealLit(1)

  // keeps the magic wand representation.
  var currentWand: MagicWand = null

  var lhsID = 0 // Id of the current package statement being translated (used to handle old[lhs])
  var activeWandsStack: List[Int] = Nil // stack of active package statements being translated

  //use this to generate unique names for states
  private val names = new BoogieNameGenerator()
  private val inverseFuncs = ListBuffer[Func]()
  private val rangeFuncs = ListBuffer[Func]()
  private val neverTrigFuncs = ListBuffer[Func]()

  // states variables
  var OPS: StateRep = null
  tempCurState = null
  nestingDepth = 0

  def name = "Wand Module"

  def wandToShapes: Map[MagicWandStructure.MagicWandStructure, DefaultWandModule.this.WandShape] = {
    val result = lazyWandToShapes match {
      case Some(m) => m
      case None =>
        mainModule.verifier.program.magicWandStructures.map(wandStructure => {
          val wandName = names.createUniqueIdentifier("wand")
          val wandType = heapModule.wandFieldType(wandName)
          //get all the expressions which form the "holes" of the shape,
          val arguments = wandStructure.subexpressionsToEvaluate(mainModule.verifier.program)

          //for each expression which is a "hole" in the shape we create a local variable declaration which will be used
          //for the function corresponding to the wand shape
          var i = 0
          val argsWand = (for (arg <- arguments) yield {
            i = i + 1
            LocalVarDecl(Identifier("arg" + i)(wandNamespace), typeModule.translateType(arg.typ), None)
          })

          val wandFun = Func(Identifier(wandName)(wandNamespace), argsWand, wandType)

          (wandStructure -> wandFun)
        }).toMap
    }
    lazyWandToShapes = Some(result)
    result
  }

  override def reset() = {
    lazyWandToShapes = None
  }

  override def preamble = wandToShapes.values.collect({
    case fun@Func(name,args,typ,_) =>
      val vars = args.map(decl => decl.l)
      val f0 = FuncApp(name,vars,typ)
      val f1 = FuncApp(heapModule.wandMaskIdentifier(name), vars, heapModule.predicateMaskFieldTypeOfWand(name.name)) // w#sm (wands secondary mask)
      val f2 = FuncApp(heapModule.wandFtIdentifier(name), vars, heapModule.predicateVersionFieldTypeOfWand(name.name)) // w#ft (permission to w#fm is added when at the begining of a package statement)
      val f3 = wandMaskField(f2) // wandMaskField (wandMaskField(w) == w#sm)
      val typeDecl: Seq[TypeDecl] = heapModule.wandBasicType(name.preferredName) match {
        case named: NamedType => TypeDecl(named)
        case _ => Nil
      }
      typeDecl ++
        fun ++
        Func(heapModule.wandMaskIdentifier(name), args, heapModule.predicateMaskFieldTypeOfWand(name.name)) ++
        Func(heapModule.wandFtIdentifier(name), args, heapModule.predicateVersionFieldTypeOfWand(name.name)) ++
      Axiom(MaybeForall(args, Trigger(f0),heapModule.isWandField(f0))) ++
        Axiom(MaybeForall(args, Trigger(f2),heapModule.isWandField(f2))) ++
        Axiom(MaybeForall(args, Trigger(f0),heapModule.isPredicateField(f0).not)) ++
        Axiom(MaybeForall(args, Trigger(f2),heapModule.isPredicateField(f2).not)) ++
        Axiom(MaybeForall(args, Trigger(f3), f1 === f3))
    }).flatten[Decl].toSeq ++ {
      MaybeCommentedDecl("Function for trigger used in checks inside wand which are never triggered",
        neverTrigFuncs.toSeq)
    } ++ {
      MaybeCommentedDecl("Functions used as inverse of receiver expressions in quantified permissions inside wand during inhale and exhale",
        inverseFuncs.toSeq)
    } ++ {
      MaybeCommentedDecl("Functions used to represent the range of the projection of each QP instance onto its receiver expressions for quantified permissions inside wand during inhale and exhale",
        rangeFuncs.toSeq)
    }
  /*
   * method returns the boogie predicate which corresponds to the magic wand shape of the given wand
   * if the shape hasn't yet been recorded yet then it will be stored
   */
  def getWandRepresentation(wand: sil.MagicWand):Exp = {

    //need to compute shape of wand
    val ghostFreeWand = wand

    //get all the expressions which form the "holes" of the shape,
    val arguments = ghostFreeWand.subexpressionsToEvaluate(mainModule.verifier.program)

    val shape:WandShape = wandToShapes(wand.structure(mainModule.verifier.program))

    shape match {
      case Func(name, _, typ,_) => FuncApp(name, arguments.map(arg => expModule.translateExp(arg)), typ)
    }
  }

  // returns the corresponding mask (wand#ft or wand#sm) depending on the value of 'ftsm'.
  override def getWandFtSmRepresentation(wand: sil.MagicWand, ftsm: Int): Exp = {
    //need to compute shape of wand
    val ghostFreeWand = wand

    //get all the expressions which form the "holes" of the shape,
    val arguments = ghostFreeWand.subexpressionsToEvaluate(mainModule.verifier.program)

    val shape:WandShape = wandToShapes(wand.structure(mainModule.verifier.program))

    shape match {
      case Func(name, _, typ,_) =>
        if(ftsm == 0){
          FuncApp(heapModule.wandFtIdentifier(name), arguments.map(arg => expModule.translateExp(arg)), typ)
        }else if(ftsm == 1){
          FuncApp(heapModule.wandMaskIdentifier(name), arguments.map(arg => expModule.translateExp(arg)), typ)
        }else{
          throw new RuntimeException()
        }
    }
  }


  /**
    * @param wand wand to be packaged
    * @param boolVar boolean variable to which the newly generated boolean variable associated with the package should
    *                be set to in the beginning
    * @return Structure that contains all the necessary blocks to initiate a package.
    *         These blocks are: LHS state
    *                           Used state (which is a new fresh state that is set as current state)
    *                           Statement with the necessary boogie code for package initiation
    *
    * Postcondition: state is set to the "Used" state generated in the function
    */
  private def packageInit(wand:sil.MagicWand, boolVar: Option[LocalVar], mainError: PartialVerificationError):PackageSetup = {
    val StateSetup(usedState, initStmt) = createAndSetState(boolVar, "Used", false).asInstanceOf[StateSetup]

    //inhale left hand side to initialize hypothetical state
    val hypName = names.createUniqueIdentifier("Ops")
    val StateSetup(hypState,hypStmt) = createAndSetState(None,"Ops")
    OPS = hypState
    UNIONState = OPS
    nestingDepth += 1
    val inhaleLeft = MaybeComment("Inhaling left hand side of current wand into hypothetical state",
      exchangeAssumesWithBoolean(expModule.checkDefinednessOfSpecAndInhale(wand.left, mainError, hypState::Nil, true), hypState.boolVar))

    val defineLhsState = stmtModule.translateStmt(sil.Label("lhs"+lhsID, Nil)(wand.pos, wand.info), hypState::Nil, hypState.boolVar, true)
    activeWandsStack = activeWandsStack:+lhsID
    lhsID += 1

    stateModule.replaceState(usedState.state)

    PackageSetup(hypState, usedState, hypStmt ++ initStmt ++ inhaleLeft ++ defineLhsState)
  }





  // -------------------------------------------------------------
  // TD: Sound package
  // -------------------------------------------------------------

  val H: LocalVar = LocalVar(Identifier("H")(transferNamespace), MapType(Seq(heapModule.heapType), Bool))
  val H_temp: LocalVar = LocalVar(Identifier("H_temp")(transferNamespace), MapType(Seq(heapModule.heapType), Bool))
  val M: LocalVar = LocalVar(Identifier("M")(transferNamespace), MapType(Seq(heapModule.heapType), maskType))
  val M_temp: LocalVar = LocalVar(Identifier("M_temp")(transferNamespace), MapType(Seq(heapModule.heapType), maskType))
  val Theta: LocalVar = LocalVar(Identifier("Theta")(transferNamespace), MapType(Seq(heapModule.heapType), maskType))
  val Theta_temp: LocalVar = LocalVar(Identifier("Theta_temp")(transferNamespace), MapType(Seq(heapModule.heapType), maskType))

  val oldTheta: LocalVar = LocalVar(Identifier("oldTheta")(transferNamespace), MapType(Seq(heapModule.heapType), maskType))

  val bm: LocalVar = LocalVar(Identifier("bm")(transferNamespace),
    MapType(Seq(heapModule.heapType, heapModule.refType, heapModule.fieldType), Bool, Seq(TypeVar("A"), TypeVar("B"))))


  val obj = LocalVarDecl(Identifier("o")(transferNamespace), heapModule.refType)
  val field = LocalVarDecl(Identifier("f")(transferNamespace), heapModule.fieldType)

  def initPackage(): Stmt = {

    val (_, state) = stateModule.freshTempState("temp")
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    val decl = LocalVarDecl(t.name, t.typ)
    val H_h: Exp = MapSelect(H, Seq(t))
    val M_h: Exp = MapSelect(M, Seq(t))


    //val H_h: Exp = MapSelect(H, Seq(h.l))
    // Can be ignored because H is underspecified
    // Because of GoodMask
    //val H_true: Exp = Forall(Seq(decl), Trigger(H_h), H_h)
    val M_zero: Exp = Forall(Seq(decl), Trigger(M_h), M_h === zeroMask)
    // val H: state
    val goodState: Exp = Forall(Seq(decl), Seq(Trigger(H_h)), H_h <==> stateModule.state(decl.l, M_h))

    stateModule.replaceState(state) // go back to the original state

    MaybeCommentBlock("Initialization for package",
      Seq(Havoc(H), Havoc(M), Assume(M_zero), Assume(goodState)))
  }

  def updateValid(H_h: Exp, M_temp_h: Exp, H_temp_h: Exp, decl: LocalVarDecl): Stmt = {
    val asm1: Exp = Forall(Seq(decl), Seq(Trigger(H_h), Trigger(H_temp_h)), H_temp_h <==> (H_h && stateModule.state(decl.l, M_temp_h)))
    val h_xf: Exp = MapSelect(decl.l, Seq(obj.l, field.l))
    val m_xf: Exp = MapSelect(M_temp_h, Seq(obj.l, field.l))
    val asm2: Exp = Forall(Seq(decl), Seq(Trigger(H_h), Trigger(H_temp_h)), H_temp_h <==> (H_h && (
      Forall(Seq(obj, field), Seq(Trigger(h_xf), Trigger(m_xf)),
        (m_xf > boogieNoPerm) ==> (h_xf !== heapModule.translateNull),
        heapModule.fieldType.freeTypeVars
      )
    )))

    Seqn(Seq(Assume(asm1), Assume(asm2)))
  }

  def updateValidWithTheta(H_h: Exp, M_temp_h: Exp, H_temp_h: Exp, Theta_h: Exp, Theta_temp_h: Exp, decl: LocalVarDecl): Stmt = {

    val asm_sum: Exp = Forall(Seq(decl), Trigger(Theta_temp_h),
      sumMask(Theta_temp_h, M_temp_h, Theta_h))

    val asm1: Exp = Forall(Seq(decl), Seq(Trigger(H_h), Trigger(H_temp_h), Trigger(Theta_temp_h)),
      H_temp_h <==> (H_h && goodMask(Theta_temp_h)))

    val h_xf: Exp = MapSelect(decl.l, Seq(obj.l, field.l))
    val Theta_xf: Exp = MapSelect(Theta_temp_h, Seq(obj.l, field.l))

    val asm2: Exp = Forall(Seq(decl), Seq(Trigger(H_h), Trigger(H_temp_h)), H_temp_h <==> (H_h && (
      Forall(Seq(obj, field), Seq(Trigger(h_xf), Trigger(Theta_xf)),
        (Theta_xf > boogieNoPerm) ==> (h_xf !== heapModule.translateNull),
        heapModule.fieldType.freeTypeVars
      )
      )))

    Seqn(Seq(Havoc(Theta_temp), Seq(Assume(asm_sum)), Seq(Assume(asm1), Assume(asm2))))
  }


  private def renamedLocalValueExp(vs: Seq[sil.LocalVarDecl], exp: sil.Exp): (Seq[sil.LocalVarDecl],
    Seq[LocalVarDecl], Seq[LocalVarDecl], sil.Exp, sil.AccessPredicate) = {
    val vsFresh = vs.map(v => mainModule.env.makeUniquelyNamed(v))
    vsFresh.foreach(vFresh => mainModule.env.define(vFresh.localVar))
    val translatedLocalDecls = vsFresh.map(v => mainModule.translateLocalVarDecl(v))
    val renamedExp = Expressions.renameVariables(exp, vs.map(v => v.localVar), vsFresh.map(vFresh => vFresh.localVar))
    val recvExp: sil.AccessPredicate = renamedExp match {
      case sil.Implies(_, recv: sil.AccessPredicate) => recv
      case other: sil.AccessPredicate => other
    }
    val qpValDecls = recvExp match {
      case sil.FieldAccessPredicate(loc@sil.FieldAccess(recv, _), _) =>
        Seq(LocalVarDecl(Identifier("o")(transferNamespace), heapModule.refType))
      case sil.PredicateAccessPredicate(sil.PredicateAccess(args, predname), _) =>
        val formalArgs = program.findPredicate(predname).formalArgs.map(mainModule.env.makeUniquelyNamed)
        formalArgs.map(_.localVar).map(mainModule.env.define)
        val formalArgDecls = formalArgs.map(mainModule.translateLocalVarDecl)
        formalArgs.map(_.localVar).map(mainModule.env.undefine)
        formalArgDecls
    }
    (vsFresh, translatedLocalDecls, qpValDecls, renamedExp, recvExp)
  }

  private def reduceForOne[T](cond: Node => Boolean, get: Node => T, default: T, target: Node): T =
    target.reduce((node: Node, subNodes: Seq[T]) => node match {
      case n: T if cond(n) => get(n)
      case _ =>
        val targets = subNodes.filter(!_.equals(default))
        if (targets.isEmpty) default else targets.head
    })

  private def validTriggerOrAdd(trigs: Seq[Trigger], find: Seq[Exp],add: Exp): Seq[Trigger] = {
    trigs.map(trig => if(reduceForOne(n => find.contains(n), _ => true, false, trig)) trig else Trigger(trig.exps ++ add))
  }

  def quantifyQPAssumes(QPAssumes: Stmt, vsFresh: Seq[sil.LocalVarDecl],translatedLocalDecls: Seq[LocalVarDecl],
                        qpValDecls: Seq[LocalVarDecl]): (Stmt, FuncApp, ListBuffer[FuncApp])= {
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    val decl = LocalVarDecl(t.name, t.typ)
    val H_h: Exp = MapSelect(H, Seq(t))
    val H_temp_h: Exp = MapSelect(H_temp, Seq(t))
    val M_h: Exp = MapSelect(M, Seq(t))
    val M_temp_h: Exp = MapSelect(M_temp, Seq(t))

    val QPMask = reduceForOne(
      { case HavocImpl(vars) if !vars.isEmpty && vars.head.name.name.startsWith("QPMask") => true; case _ => false },
      { case HavocImpl(vars) => vars.head }, FalseLit(), QPAssumes)

    def indepFieldStmt(v: LocalVarDecl) = v.typ match {
      case NamedType("Field", _) => true
      case _ => false
    }

    val tempMask = permModule.currentMask(0)
    val quantifyOverTempHeapAndGoodHeap: PartialFunction[Node, Node] = {
      // add !H[h] to independent objects or predicate
      case Forall(expVars, trigger, BinExp(UnExp(Not, lft), Implies, rht), tvs)  =>
        Forall(Seq(decl) ++ expVars, validTriggerOrAdd(trigger, Seq(t, QPMask, tempMask), H_h), (H_h && lft).not ==> rht, tvs).replace(QPMask, M_temp_h).replace(tempMask, M_h)
      // no H[h] to independent field
      case Forall(expVars, trigger, BinExp(lft, Implies, rht), tvs) if indepFieldStmt(expVars.last) =>
        Forall(Seq(decl) ++ expVars, validTriggerOrAdd(trigger, Seq(t, QPMask, tempMask), H_h), lft ==> rht, tvs).replace(QPMask, M_temp_h).replace(tempMask, M_h)
      // add H[h] to target object and field
      case Forall(expVars, trigger, BinExp(lft, Implies, rht), tvs) =>
        Forall(Seq(decl) ++ expVars, validTriggerOrAdd(trigger, Seq(t, QPMask, tempMask), H_h), (H_h && lft) ==> rht, tvs).replace(QPMask, M_temp_h).replace(tempMask, M_h)
      // add H[h] to target object and field and !H[h] to independent object
      case Forall(expVars, trigger, BinExp(BinExp(llft, Implies, lrht), And, BinExp(UnExp(Not, rlft), Implies, rrht)), tvs) =>
        Forall(Seq(decl) ++ expVars, validTriggerOrAdd(trigger, Seq(t, QPMask, tempMask), H_h), ((H_h && llft) ==> lrht) && ((H_h && rlft).not ==> rrht), tvs).replace(QPMask, M_temp_h).replace(tempMask, M_h)
    }

    def unrelatedCheck(n: Node) = n match {
      case Assume(FuncApp(_, Seq(`t`, _), _)) => true
      case HavocImpl(Seq(`QPMask`)) => true
      case Assign(_, `QPMask`) => true
      case _ => false
    }

    var rangeFunApp: FuncApp = null
    val invFunApps = ListBuffer[FuncApp]()
    val invRecvFuncNames = ListBuffer[String]()
    var qpRangeFuncName: String = null
    var neverTrigFuncName: String = null
    val replaceFuncsAndStore: PartialFunction[Node, Node] = {
      case all@FuncApp(Identifier(name, ns), args, typ) =>
        if (name.startsWith("qpRange") && qpRangeFuncName != name) {
          qpRangeFuncName = name
          rangeFuncs.append(Func(Identifier(name ++ "InWand")(ns), Seq(decl) ++ qpValDecls, typ))
          rangeFunApp = FuncApp(Identifier(name ++ "InWand")(ns), Seq(t) ++ qpValDecls.map(_.l), typ)
        } else if (name.startsWith("invRecv") && !invRecvFuncNames.contains(name)) {
          invRecvFuncNames.append(name)
          inverseFuncs.append(Func(Identifier(name ++ "InWand")(ns), Seq(decl) ++ qpValDecls, typ))
          invFunApps.append(FuncApp(Identifier(name ++ "InWand")(ns), Seq(t) ++ qpValDecls.map(_.l), typ));
        } else if (name.startsWith("neverTriggered") && neverTrigFuncName != name) {
          neverTrigFuncName = name
          neverTrigFuncs.append(Func(Identifier(name ++ "InWand")(ns), Seq(decl) ++ translatedLocalDecls, typ))
        }
        if (name.startsWith("qpRange") || name.startsWith("invRecv") || name.startsWith("neverTriggered")) {
          FuncApp(Identifier(name ++ "InWand")(ns), Seq(t) ++ args, typ)
        } else {
          all
        }
    }
    (QPAssumes
      .reduce[Stmt]((node: Node, subStmts: Seq[Stmt]) => node match {
        case s@CommentBlock(_, stmt) if reduceForOne(unrelatedCheck _, _ => false, true, stmt) => s ++ subStmts
        case _ => subStmts
      })
      .transform(quantifyOverTempHeapAndGoodHeap andThen (_.transform(replaceFuncsAndStore)(_ => true)))(), rangeFunApp, invFunApps)
  }

  def inhaleLHS(A: sil.Exp, pc: Exp = TrueLit()): Stmt = {

    val (_, state) = stateModule.freshTempState("temp")
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    val decl = LocalVarDecl(t.name, t.typ)
    val H_h: Exp = MapSelect(H, Seq(t))
    val H_temp_h: Exp = MapSelect(H_temp, Seq(t))
    val M_h: Exp = MapSelect(M, Seq(t))
    val M_temp_h: Exp = MapSelect(M_temp, Seq(t))

    val r: Stmt =
      A match {
        case _ if A.isPure =>
          val e: Exp = expModule.translateExp(A)
          val asm: Exp = Forall(Seq(decl), Trigger(H_temp_h), H_temp_h <==> (H_h && (pc ==> e)))
          MaybeCommentBlock("inhaling pure expression for all states",
            Seq(Havoc(H_temp), Assume(asm), Assign(H, H_temp)))
        case sil.And(a, b) => Seqn(Seq(inhaleLHS(a, pc), inhaleLHS(b, pc)))
        case sil.AccessPredicate(loc: sil.LocationAccess, prm: sil.Exp) =>
          val perm = translatePerm(prm)
          val rf: Seq[Exp] = loc match {
            case sil.FieldAccess(rcv, field) =>
              Seq(expModule.translateExp(rcv), heapModule.translateLocation(loc))
            case sil.PredicateAccess(_, _) =>
              Seq(heapModule.translateNull, heapModule.translateLocation(loc))
          }
          val update_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(M_h)), pc ==> (M_temp_h === MapUpdate(M_h, rf, MapSelect(M_h, rf) + perm)))
          //val perm_pos: Stmt = Assert(Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(M_h)),
           // (pc && H_h)==> (perm > boogieNoPerm)), error.dueTo(reasons.NegativePermission(prm)))
          val same_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(M_h)), UnExp(Not, pc) ==> (M_temp_h === M_h))
          MaybeCommentBlock("inhaling location permission (heap loc or predicate)",
            Seq(Havoc(H_temp), Havoc(M_temp), Assume(update_mask), Assume(same_mask), updateValid(H_h, M_temp_h, H_temp_h, decl), Assign(H, H_temp), Assign(M, M_temp)))
        case w@sil.MagicWand(_, _) =>
          val perm = boogieFullPerm
          val rf: Seq[Exp] = Seq(heapModule.translateNull, getWandRepresentation(w))
          val update_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(M_h)), pc ==> (M_temp_h === MapUpdate(M_h, rf, MapSelect(M_h, rf) + perm)))
          val same_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(M_h)), UnExp(Not, pc) ==> (M_temp_h === M_h))
          MaybeCommentBlock("inhaling permission to wand",
            Seq(Havoc(H_temp), Havoc(M_temp), Assume(update_mask), Assume(same_mask), updateValid(H_h, M_temp_h, H_temp_h, decl), Assign(H, H_temp), Assign(M, M_temp)))
        case sil.Implies(cond, a) => inhaleLHS(a, pc && expModule.translateExp(cond))
        case sil.CondExp(cond, a, b) =>
          val tcond = expModule.translateExp(cond)
          Seqn(Seq(inhaleLHS(a, pc && tcond), inhaleLHS(b,  pc && UnExp(Not, tcond))))
        case qp@sil.Forall(vs, triggers, exp) =>
          val QPAssumes = inhaleModule.inhale(Seq((qp, errors.InhaleFailed(sil.Inhale(qp)()))), null)
          val (vsFresh, translatedLocalDecls, qpValDecls, _, _) = renamedLocalValueExp(vs, exp)
          val (update_mask, _, _) = quantifyQPAssumes(QPAssumes, vsFresh, translatedLocalDecls, qpValDecls)
          val same_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(M_h)), UnExp(Not, pc) ==> (M_temp_h === M_h))
          vsFresh.foreach(vFresh => mainModule.env.undefine(vFresh.localVar))
          MaybeCommentBlock("inhaling quantified location permission (heap loc or predicate)",
            Seq(Havoc(H_temp), Havoc(M_temp), update_mask, Assume(same_mask), updateValid(H_h, M_temp_h, H_temp_h, decl), Assign(H, H_temp), Assign(M, M_temp)))
      }
    stateModule.replaceState(state) // go back to the original state
    r
  }

  // More fine-grained:
  // Could check for which fields we can use a minimum
  def accessesDetGaurav(a: sil.Exp): Set[Object] = {
    val cannot_use_minimum = scala.collection.mutable.Set[Object]()

    a.foreach(
      e1 => e1 match {
        case sil.AccessPredicate(loc, prm) if prm.existsDefined(
          e2 => e2 match {
            case sil.FieldAccess(_, _) => true
          }
        ) => loc match {
          case sil.PredicateAccess(_, name) => cannot_use_minimum.add(name)
          case sil.FieldAccess(_, field) => cannot_use_minimum.add(field)
          }

        case _ => ()
      }
    )
    //println("Cannot use min", cannot_use_minimum)
    cannot_use_minimum.toSet
  }


  // heap_loc and p should not depend on h, only on the state from outside
  // hence we do not create a fresh state
  // m is a mask
  // should have enough perm from heuristic
  def addPermFromOutside(heap_loc: Seq[Exp], m: LocalVar, error: VerificationError): Stmt = {

    val newMask: LocalVarDecl = LocalVarDecl(Identifier("newMask")(transferNamespace), maskType)

    val curr_perm: Exp = MapSelect(currentMask.head, heap_loc)
    val old_heap = heapModule.currentHeap.head
    val old_mask = currentMask.head


    var rstate = stateModule.freshTempState("temp")
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    val decl = LocalVarDecl(t.name, t.typ)
    val H_h: Exp = MapSelect(H, Seq(t))
    val H_temp_h: Exp = MapSelect(H_temp, Seq(t))
    val M_h: Exp = MapSelect(M, Seq(t))
    val M_temp_h: Exp = MapSelect(M_temp, Seq(t))
    val Theta_h: Exp = MapSelect(Theta, Seq(t))
    val Theta_h_xf: Exp = MapSelect(Theta_h, Seq(obj.l, field.l))
    val Theta_temp_h: Exp = MapSelect(Theta_temp, Seq(t))

    val M_temp_xf: Exp = MapSelect(M_temp_h, Seq(obj.l, field.l))
    val M_xf: Exp = MapSelect(M_h, Seq(obj.l, field.l))
    val m_xf: Exp = MapSelect(m, Seq(obj.l, field.l))
    val mask_xf: Exp = MapSelect(old_mask, Seq(obj.l, field.l))
    val new_mask_xf: Exp = MapSelect(newMask.l, Seq(obj.l, field.l))

    val old_heap_xf = MapSelect(old_heap, Seq(obj.l, field.l))

    val h_xf = MapSelect(t, Seq(obj.l, field.l))



    // Should have enough permission
    // if combinableWands, then we flatten the mask
    // Only for heap locations


    //((staticGoodMask && heapModule.isPredicateField(field.l).not && heapModule.isWandField(field.l).not) ==> perm <= fullPerm )))

    val update_masks: Exp = {
      if (verifier.wandType == 0)
        Forall(Seq(decl, obj, field), Seq(Trigger(M_temp_xf), Trigger(M_xf)), M_temp_xf === M_xf + m_xf)
      else {
        ((Forall(Seq(decl, obj, field), Seq(Trigger(M_temp_xf), Trigger(M_xf), Trigger(Theta_h_xf)),
          (heapModule.isPredicateField(field.l).not && heapModule.isWandField(field.l).not)
            ==> (M_temp_xf === permModule.minReal(M_xf + m_xf, permModule.fullPerm - Theta_h_xf))))
        &&
        (Forall(Seq(decl, obj, field), Seq(Trigger(M_temp_xf), Trigger(M_xf), Trigger(Theta_h_xf)),
          (heapModule.isPredicateField(field.l) || heapModule.isWandField(field.l))
          ==> (M_temp_xf === M_xf + m_xf))))
      }
    }
    val equateMasks: Exp = Forall(Seq(decl), Seq(Trigger(H_temp_h), Trigger(H_h)), H_temp_h <==> (H_h &&
      (Forall(Seq(obj, field), Seq(Trigger(m_xf), Trigger(old_heap_xf), Trigger(h_xf)), (m_xf > boogieNoPerm ==>
        (old_heap_xf === h_xf)), heapModule.fieldType.freeTypeVars))
    ))


    val bm_h_hl: Exp = MapSelect(bm, Seq(t, obj.l, field.l))

    val pruneStates: Exp = {
      if (verifier.wandType >= 2) {
        Forall(Seq(decl), Seq(Trigger(H_temp_h), Trigger(H_h)), H_temp_h <==> (H_h &&
          (Forall(Seq(obj, field),
            Seq(Trigger(bm_h_hl), Trigger(m_xf)),
            bm_h_hl ==> (m_xf === boogieNoPerm),
            heapModule.fieldType.freeTypeVars))
          ))
      }
      else H === H_temp
    }


    val subtract_mask: Exp =
      Forall(Seq(obj, field), Seq(Trigger(new_mask_xf), Trigger(mask_xf), Trigger(m_xf)),
        new_mask_xf === mask_xf - m_xf
      )

    stateModule.replaceState(rstate._2) // go back to the original state

    MaybeCommentBlock("adding permission from outside",
    Seq(
      Havoc(newMask.l),
      Assume(subtract_mask),
      Assign(old_mask, newMask.l),
      //Assign(curr_perm, curr_perm - p),


      Havoc(H_temp),
      Assume(pruneStates),
      Assign(H, H_temp),

      Havoc(H_temp),
      Assume(equateMasks),
      Assign(H, H_temp),


      Havoc(M_temp),
      Havoc(H_temp),

      Assume(update_masks),
      updateValidWithTheta(H_h, M_temp_h, H_temp_h, Theta_h, Theta_temp_h, decl),
      Assign(H, H_temp),
      Assign(M, M_temp)
    ))
  }

  // can_use_minimum --> cannot_use_minimum = set of Exp

  // Should be enough to sat one, but incomplete
  def heuristicHowMuchPerm(heap_loc: Seq[Exp], ff: Object, p: Exp, pc: Exp, error: VerificationError, cannot_use_minimum: Set[Object], is_heap_dep_pred: Boolean = false): (LocalVarDecl, Stmt) = {
    // heap_loc_out: Seq[Exp],

    //println(heap_loc, heap_loc_out, p)

    val m: LocalVarDecl = LocalVarDecl(Identifier("m")(transferNamespace), maskType)


    val currentPerm: Exp = MapSelect(currentMask.head, heap_loc)
    val old_mask = currentMask.head

    val rstate = stateModule.freshTempState("temp")
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    val decl = LocalVarDecl(t.name, t.typ)
    val H_h: Exp = MapSelect(H, Seq(t))
    val H_temp_h: Exp = MapSelect(H_temp, Seq(t))
    val M_h: Exp = MapSelect(M, Seq(t))
    val M_temp_h: Exp = MapSelect(M_temp, Seq(t))

    val conj: Exp = H_h && pc


    val possible: Exp = Forall(Seq(decl), Seq(Trigger(M_h), Trigger(H_h)), conj ==> (
        MapSelect(M_h, heap_loc) + currentPerm >= p
      ))

    // Needs a forall
    val any_hl = Seq(obj.l, field.l)
    val old_hl = MapSelect(old_mask, any_hl)
    val m_hl = MapSelect(m.l, any_hl)
    val smaller: Exp = Forall(Seq(obj, field), Seq(Trigger(m_hl), Trigger(old_hl)),
      old_hl >= m_hl
      )



    val sufficient: Exp = Forall(Seq(decl), Seq(Trigger(M_h), Trigger(H_h)), conj ==> (
      MapSelect(M_h, heap_loc) + MapSelect(m.l, heap_loc) >= p
      ))

    //val ff_out: Exp = heap_loc_out.tail.head
    //println("ff_out", ff_out)



    val rff = heap_loc.tail.head
    val m_hl_fixed = MapSelect(m.l, Seq(obj.l, rff))

    var no_ref_evaluates_to_this: Exp = Forall(Seq(obj), Trigger(m_hl_fixed),
      (Forall(Seq(decl), Trigger(H_h),
        conj ==> (heap_loc.head !== obj.l)
      )) ==> (m_hl_fixed === boogieNoPerm))

    // TD: Let's use ff_out as the key for "can_use_minimum"
    var evaluates_minimum: Exp = {
      if (!cannot_use_minimum.contains(ff)) {
        Forall(Seq(obj), Trigger(m_hl_fixed),
          (Exists(Seq(decl), Trigger(H_h),
            conj && (heap_loc.head === obj.l)
          )) ==>
            (Exists(Seq(decl), Seq(Trigger(H_h), Trigger(M_h)),
              conj && (heap_loc.head === obj.l) &&
                MapSelect(M_h, heap_loc) + m_hl_fixed <= p
            ))
        )
      } else TrueLit()
    }

    var other_fields_same: Exp = Forall(Seq(obj, field), Trigger(m_hl),
      (field.l !== rff) ==> (m_hl === boogieNoPerm))

    if (is_heap_dep_pred)
    {
      //println("Alternative encoding for predicates", heap_loc.tail.head)
      no_ref_evaluates_to_this = Forall(Seq(obj, field), Trigger(m_hl),
        (Forall(Seq(decl), Trigger(H_h),
          conj ==> ((heap_loc.head !== obj.l) || (field.l !== rff))
        )) ==>
          (m_hl === boogieNoPerm))

      // TD: Let's use ff_out as the key for "can_use_minimum"
      evaluates_minimum = {
        if (!cannot_use_minimum.contains(ff)) {
          Forall(Seq(obj, field), Trigger(m_hl),
            (Exists(Seq(decl), Trigger(H_h),
              conj && (heap_loc.head === obj.l) && (field.l === rff)
            )) ==>
              (Exists(Seq(decl), Seq(Trigger(H_h), Trigger(M_h)),
                conj && (heap_loc.head === obj.l) &&
                  MapSelect(M_h, heap_loc) + m_hl <= p
              ))
          )
        } else TrueLit()
      }

      other_fields_same = TrueLit()
    }


    /*
     */

    // Needs to add that this can be minimum, that is there exists one for which equality holds
    // Unsound but temporarily ok



    stateModule.replaceState(rstate._2) // go back to the original state

    (m, MaybeCommentBlock("determining how much to take from the outside (heuristic)",
      Seq(
        MaybeComment("POSSIBLE", Assert(possible, error)),
        Havoc(m.l),
        Assume(smaller),
        Assume(sufficient),
        Assume(no_ref_evaluates_to_this),
        Assume(other_fields_same),
        //Assert(assert_exists_minimum_forall, error),
        Assume(evaluates_minimum)
      )))
  }

  // Also initalizes bm(h, hl): Bool
  def initTheta(): Stmt = {
    val (_, state) = stateModule.freshTempState("temp")
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    val decl = LocalVarDecl(t.name, t.typ)
    val Theta_h: Exp = MapSelect(Theta, Seq(t))
    val Theta_zero: Exp = Forall(Seq(decl), Trigger(Theta_h), Theta_h === zeroMask)

    val M_h: Exp = MapSelect(M, Seq(t))
    val M_h_hl: Exp = MapSelect(M_h, Seq(obj.l, field.l))

    val bm_h_hl: Exp = MapSelect(bm, Seq(t, obj.l, field.l))
    // Only for fields
    val bm_init: Exp = Forall(
      Seq(decl, obj, field),
      Seq(Trigger(bm_h_hl), Trigger(M_h_hl)),
      bm_h_hl <==> (heapModule.isPredicateField(field.l).not && heapModule.isWandField(field.l).not &&
        (M_h_hl >= permModule.fullPerm)))

    stateModule.replaceState(state) // go back to the original state

    MaybeCommentBlock("Initialization of theta and bm for exhale RHS",
      Seq(Havoc(Theta), Assume(Theta_zero), Havoc(bm), Assume(bm_init)))
  }



  def exhaleRHS(A: sil.Exp, mainError: PartialVerificationError, cannot_use_min: Set[Object], pc: Exp = TrueLit()): Stmt = {

    var rstate = stateModule.freshTempState("temp")
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    val decl = LocalVarDecl(t.name, t.typ)
    val H_h: Exp = MapSelect(H, Seq(t))
    val H_temp_h: Exp = MapSelect(H_temp, Seq(t))
    val M_h: Exp = MapSelect(M, Seq(t))
    val M_temp_h: Exp = MapSelect(M_temp, Seq(t))
    val error: VerificationError = mainError.dueTo(reasons.AssertionFalse(A))

    A match {
      case _ if A.isPure =>
        // Removing old[lhs]
        val e: Exp = expModule.translateExp(
          A.transform({
            case sil.LabelledOld(e, "lhs") => e
          }))
        val asm: Exp = Forall(Seq(decl), Trigger(H_h), (H_h && pc) ==> e)
        stateModule.replaceState(rstate._2) // go back to the original state
        MaybeComment("exhaling pure expression for all states",
            Assert(asm, error))

    case sil.And(a, b) =>
        stateModule.replaceState(rstate._2) // go back to the original state
        Seqn(Seq(exhaleRHS(a, mainError, cannot_use_min, pc), exhaleRHS(b, mainError, cannot_use_min, pc)))

    case sil.AccessPredicate(loc: sil.LocationAccess, prm: sil.Exp) =>
        val (rr, ff, is_pred) = loc match {
          case sil.FieldAccess(rcv, field) =>
            (expModule.translateExp(rcv), heapModule.translateLocation(loc), false)
          case p@sil.PredicateAccess(_, _) =>
            (heapModule.translateNull, heapModule.translateLocation(loc),
              (p.existsDefined(e2 => e2 match { case sil.FieldAccess(_, _) => true})) // Checks if the predicate is heap dependent
            )
        }

        val rf: Seq[Exp] = Seq(rr, ff)
        val p: Exp = expModule.translateExp(prm)

        stateModule.replaceState(rstate._2) // go back to the original state

        /*
        // Wrong if heap dependent predicate
        // Should not be translated with this heap
        val (rr_out, ff_out) = loc match {
          case sil.FieldAccess(rcv, field) =>
            (expModule.translateExp(rcv), heapModule.translateLocation(loc))
          case sil.PredicateAccess(_, _) =>
            (heapModule.translateNull, heapModule.translateLocation(loc))
        }

        val rf_out: Seq[Exp] = Seq(rr_out, ff_out)
         */

        val which_field: Object = loc match {
          case sil.FieldAccess(rcv, field) => field
          case sil.PredicateAccess(_, name) => name
        }

        //println("ff_out", ff_out)

        val (m, s1) = heuristicHowMuchPerm(rf, which_field, p, pc, mainError.dueTo(reasons.InsufficientPermission(loc)), cannot_use_min, is_pred)
        val s2 = addPermFromOutside(rf, m.l, error)
        rstate = stateModule.freshTempState("temp")

        // 1: Assert that we have enough
        val assert: Exp = Forall(Seq(decl), Seq(Trigger(M_h), Trigger(H_h)), (H_h && pc) ==> (MapSelect(M_h, rf) >= p))
        //val do_we_actually_need_extract: Exp = Forall(Seq(decl), Seq(Trigger(M_h), Trigger(H_h)), (H_h && pc) ==> (MapSelect(M_h, rf) >= p))

      val update_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(H_h), Trigger(M_h)), (H_h && pc) ==>
          (M_temp_h === MapUpdate(M_h, rf, MapSelect(M_h, rf) - p)))
        val same_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(H_h), Trigger(M_h)), UnExp(Not, H_h && pc) ==> (M_temp_h === M_h))


        val Theta_h: Exp = MapSelect(Theta, Seq(t))
        val Theta_temp_h: Exp = MapSelect(Theta_temp, Seq(t))

        val update_mask_theta: Exp = Forall(Seq(decl), Seq(Trigger(Theta_temp_h), Trigger(H_h), Trigger(Theta_h)), (H_h && pc) ==>
          (Theta_temp_h === MapUpdate(Theta_h, rf, MapSelect(Theta_h, rf) + p)))
        val same_mask_theta: Exp = Forall(Seq(decl), Seq(Trigger(Theta_temp_h), Trigger(H_h), Trigger(Theta_h)), UnExp(Not, H_h && pc) ==> (Theta_temp_h === Theta_h))




        stateModule.replaceState(rstate._2) // go back to the original state
        MaybeCommentBlock("exhaling heap location permission",
          //Seq(Havoc(H_temp), Havoc(M_temp), Assume(update_mask), Assume(same_mask), updateValid(), Assign(H, H_temp), Assign(M, M_temp)))
          Seq(
            If(UnExp(Not, assert), Seqn(Seq(s1, s2)), Seqn(Seq())),
            MaybeCommentBlock("removing permission from the states",
              Seq(Assert(assert, mainError.dueTo(reasons.InsufficientPermission(loc))),
                  Havoc(M_temp), Assume(update_mask), Assume(same_mask), Assign(M, M_temp),
            Havoc(Theta_temp), Assume(update_mask_theta), Assume(same_mask_theta), Assign(Theta, Theta_temp)
              ))))

      case w@sil.MagicWand(_, _) =>

        val wandRep = wandModule.getWandRepresentation(w)
        val (rr, ff) = (heapModule.translateNull, wandRep)
        val rf: Seq[Exp] = Seq(rr, ff)

        stateModule.replaceState(rstate._2) // go back to the original state

        val p = boogieFullPerm

        val (m, s1) = heuristicHowMuchPerm(rf, w, p, pc, error, cannot_use_min)
        val s2 = addPermFromOutside(rf, m.l, error)
        rstate = stateModule.freshTempState("temp")

        // 1: Assert that we have enough
        val assert: Exp = Forall(Seq(decl), Seq(Trigger(M_h), Trigger(H_h)), (H_h && pc) ==> (MapSelect(M_h, rf) >= p))

        val update_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(H_h), Trigger(M_h)), (H_h && pc) ==>
          (M_temp_h === MapUpdate(M_h, rf, MapSelect(M_h, rf) - p)))
        val same_mask: Exp = Forall(Seq(decl), Seq(Trigger(M_temp_h), Trigger(H_h), Trigger(M_h)), UnExp(Not, H_h && pc) ==> (M_temp_h === M_h))


        val Theta_h: Exp = MapSelect(Theta, Seq(t))
        val Theta_temp_h: Exp = MapSelect(Theta_temp, Seq(t))

        val update_mask_theta: Exp = Forall(Seq(decl), Seq(Trigger(Theta_temp_h), Trigger(H_h), Trigger(Theta_h)), (H_h && pc) ==>
          (Theta_temp_h === MapUpdate(Theta_h, rf, MapSelect(Theta_h, rf) + p)))
        val same_mask_theta: Exp = Forall(Seq(decl), Seq(Trigger(Theta_temp_h), Trigger(H_h), Trigger(Theta_h)), UnExp(Not, H_h && pc) ==> (Theta_temp_h === Theta_h))

        stateModule.replaceState(rstate._2) // go back to the original state
        MaybeCommentBlock("exhaling wand",
          //Seq(Havoc(H_temp), Havoc(M_temp), Assume(update_mask), Assume(same_mask), updateValid(), Assign(H, H_temp), Assign(M, M_temp)))
          Seq(s1, s2,
            MaybeCommentBlock("removing permission from the states",
              Seq(Assert(assert, error),
                Havoc(M_temp), Assume(update_mask), Assume(same_mask), Assign(M, M_temp),

                Havoc(Theta_temp), Assume(update_mask_theta), Assume(same_mask_theta), Assign(Theta, Theta_temp)
              ))))

      case sil.Implies(cond, a) =>
        val tcond = expModule.translateExp(cond)
        stateModule.replaceState(rstate._2) // go back to the original state
        exhaleRHS(a, mainError, cannot_use_min, pc && tcond)
      case sil.CondExp(cond, a, b) =>
        val tcond = expModule.translateExp(cond)
        stateModule.replaceState(rstate._2) // go back to the original state
        Seqn(Seq(exhaleRHS(a, mainError, cannot_use_min, pc && tcond), exhaleRHS(b, mainError, cannot_use_min, pc && UnExp(Not, tcond))))
      case qp@sil.Forall(vs, provided_triggers, exp) =>
        val (vsFresh, translatedLocalDecls, qpValDecls, renamedExp, recvExp) = renamedLocalValueExp(vs, exp)
        val translatedLocals = translatedLocalDecls.map(_.l)
        val QPAssumes = exhaleModule.exhale(Seq((qp, mainError)))
        val (qpAssumeOverHeap, rangeFunApp, invFunApps) = quantifyQPAssumes(QPAssumes, vsFresh, translatedLocalDecls, qpValDecls)
        // TODO: remove the string match?
        val NoPerm = reduceForOne(
          { case BinExp(c@Const(_), LtCmp, _) if c.name.name.startsWith("NoPerm") => true; case _ => false },
          { case BinExp(np, _, _) => np }, FalseLit(), QPAssumes)
        stateModule.replaceState(rstate._2)
        val footprintCal = exhaleRHS(renamedExp, mainError, cannot_use_min, pc)
        rstate = stateModule.freshTempState("temp")
        def haveTempVar(n: Node, rel: Seq[Boolean]): Boolean = {
          n match {
            case Forall(_, _, _, _) => false
            case Exists(_, _, _) => false
            case lv@LocalVar(_, _) if translatedLocals.contains(lv) => true
            case _ => rel.fold(false)(_ || _)
          }
        }
        def haveMask(n: Node, rel: Seq[Boolean]): Boolean = {
          n match {
            case Trigger(_) => false
            case M_h | Theta => true
            case _ => rel.fold(false)(_ || _)
          }
        }
        def haveTempMask(n: Node, rel: Seq[Boolean]): Boolean = {
          n match {
            case Trigger(_) => false
            case M_temp_h => true
            case _ => rel.fold(false)(_ || _)
          }
        }
        val propertyStmts = qpAssumeOverHeap
          .reduce[Stmt]((node: Node, subStmts: Seq[Stmt]) => node match {
            case s@CommentBlock(_, stmt) if !stmt.reduce(haveTempMask) && !stmt.reduce(haveMask) => s ++ subStmts
            case _ => subStmts
          })
        val removeStmts = qpAssumeOverHeap
          .reduce[Stmt]((node: Node, subStmts: Seq[Stmt]) => node match {
            case s@CommentBlock(_, stmt) if stmt.reduce(haveTempMask) => s ++ subStmts
            case _ => subStmts
          })

        def localVarToInvFunc(exp: Exp): Exp = translatedLocals.zip(invFunApps).foldLeft(exp)((exp: Exp, t: (LocalVar, FuncApp)) => exp.replace(t._1, t._2))
        val expPerm = expModule.translateExp(recvExp.perm)
        /*
        1. add qpDecl to forall variables
        2. map recv expression to qpDecl variables by expRecvToQpVal
        2. map local variables to invRecvs(recv's) by localVarToInvFunc
         */
        val quantifyOverVar: PartialFunction[Node, Node] = recvExp match {
          case sil.FieldAccessPredicate(loc@sil.FieldAccess(recv, _), _) =>
            val qpDecl = qpValDecls.head
            val qpVar = qpDecl.l
            val expRecv = expModule.translateExp(recv)
            val expRecvToQpVal = (exp: Exp) => exp.replace(expRecv, qpVar)
            val eqInvs = BinExp(expRecv, EqCmp, qpVar)
            val ff = heapModule.translateLocation(loc)
            val auto_trigger = Trigger(MapSelect(M_h, qpVar ++ ff))
            val quantifyOverVar: PartialFunction[Node, Node] = {
              // target object and field
              case Forall(vars, triggers, exp@BinExp(lft, Implies, rht), tvs) if exp.reduce(haveTempVar) && exp.reduce(haveMask) =>
                val newLft = BinExp(lft, And, BinExp(BinExp(NoPerm, LtCmp, expPerm), And, rangeFunApp))
                Forall(qpDecl ++ vars, triggers.map(x => Trigger(x.exps ++ auto_trigger.exps)), localVarToInvFunc(newLft ==> (eqInvs && expRecvToQpVal(rht))), tvs)
              // independent object and field
              case Forall(vars, triggers, exp@BinExp(lft, Implies, rht), tvs) if exp.reduce(haveTempVar) =>
                Forall(vars, triggers.map(x => Trigger(x.exps ++ auto_trigger.exps)), localVarToInvFunc(exp), tvs)
              // minimum guarantee
              case Forall(vars, triggers, min@BinExp(Exists(lv, lt, lft), Implies, Exists(rv, rt, rht)), tvs) =>
                Forall(vars, triggers, localVarToInvFunc(min), tvs)
            }
            quantifyOverVar
          case sil.PredicateAccessPredicate(sil.PredicateAccess(args, predname), _) =>
            val qpDecls = qpValDecls
            val qpVars = qpDecls.map(_.l)
            val expRecvs = args.map(expModule.translateExp)
            val expRecvToQpVal = (exp: Exp) => expRecvs.zip(qpVars).foldLeft(exp)((inExo: Exp, t: (Exp, LocalVar)) => inExo.replace(t._1, t._2))
            val eqInvs = expRecvs.tail.zip(qpVars.tail).foldLeft(BinExp(expRecvs.head, EqCmp, qpVars.head))((inExp: Exp, t: (Exp, LocalVar)) => BinExp(inExp, And, BinExp(t._1, EqCmp, t._2)))
            val ff = heapModule.translateLocation(program.findPredicate(predname), qpVars)
            val auto_trigger = Trigger(MapSelect(M_h, heapModule.translateNull ++ ff))
            val quantifyOverVar: PartialFunction[Node, Node] = {
              // target object and field
              case Forall(vars, triggers, exp@BinExp(lft, Implies, rht), tvs) if exp.reduce(haveTempVar) && exp.reduce(haveMask) =>
                val newLft = (lft && (NoPerm < expPerm)) &&  rangeFunApp
                Forall(qpDecls ++ vars, triggers.map(x => Trigger(x.exps ++ auto_trigger.exps)), localVarToInvFunc(newLft ==> (eqInvs && expRecvToQpVal(rht))), tvs)
              // independent object
              case Forall(vars, triggers, exp@BinExp(Forall(inVars, inTriggers, BinExp(inLft, Implies, inRht), inTvs), Implies, rht), tvs) if exp.reduce(haveTempVar) =>
                Forall(qpDecls, triggers.map(t => Trigger(t.exps.map(expRecvToQpVal))), Forall(inVars, inTriggers, inLft ==> eqInvs.not, inTvs) ==> expRecvToQpVal(rht), tvs).replace(vars.head.l, heapModule.translateNull)
              // independent filed
              case Forall(vars, triggers, exp@BinExp(BinExp(_, NeCmp, _), Implies, rht), tvs) if exp.reduce(haveTempVar) =>
                val newLft = ((vars.head.l !== heapModule.translateNull) || heapModule.isPredicateField(vars.last.l).not) || (heapModule.getPredicateId(vars.last.l) !== IntLit(heapModule.getPredicateId(predname)))
                Forall(vars, triggers.map(t => Trigger(t.exps.map(expRecvToQpVal))), newLft ==> expRecvToQpVal(rht), tvs)
              // minimum guarantee
              case Forall(vars, triggers, min@BinExp(Exists(_, _, _), Implies, Exists(_, _, _)), tvs) =>
                Forall(qpDecls, triggers.map(t => Trigger(t.exps.map(expRecvToQpVal))), localVarToInvFunc(min.replace(heapModule.translateNull === vars.head.l, eqInvs)), tvs)

            }
            quantifyOverVar
        }
        val footprintCalOverVar = footprintCal
          .reduce[Stmt]((node: Node, subStmts: Seq[Stmt]) => node match {
            case s@If(_, _, _) => MaybeCommentBlock("exhaling heap location permission", s)
            case s@Assert(_, _) => MaybeCommentBlock("removing permission from the states",s)
            case _ => subStmts
          }).transform(quantifyOverVar)()
        val ret = Seq(propertyStmts, footprintCalOverVar,
                          Havoc(M_temp), removeStmts, Assign(M, M_temp),
                          Havoc(Theta_temp), removeStmts.replace(M, Theta).replace(M_temp, Theta_temp), Assign(Theta, Theta_temp))
        vsFresh.foreach(vFresh => mainModule.env.undefine(vFresh.localVar))
        stateModule.replaceState(rstate._2)
        MaybeCommentBlock("exhaling wand", ret)
    }
  }

  def handleProofScript(P: sil.Stmt, error: PartialVerificationError, cannot_use_min: Set[Object], pc: Exp = TrueLit()): Stmt = {

    var rstate = stateModule.freshTempState("temp")
    val t: LocalVar = heapModule.currentHeap.head.asInstanceOf[LocalVar]
    stateModule.replaceState(rstate._2) // go back to the original state

    val decl = LocalVarDecl(t.name, t.typ)
    val M_h: Exp = MapSelect(M, Seq(t))
    val M_h_xf: Exp = MapSelect(M_h, Seq(obj.l, field.l))
    val M_temp_h: Exp = MapSelect(M_temp, Seq(t))
    val M_temp_h_xf: Exp = MapSelect(M_temp_h, Seq(obj.l, field.l))

    val Theta_h: Exp = MapSelect(Theta, Seq(t))
    val Theta_h_xf: Exp = MapSelect(Theta_h, Seq(obj.l, field.l))
    val oldTheta_h: Exp = MapSelect(oldTheta, Seq(t))
    val oldTheta_h_xf: Exp = MapSelect(oldTheta_h, Seq(obj.l, field.l))


    def exhaleRestore(a: sil.Exp) =
    {
      (oldTheta := Theta) ++ exhaleRHS(a, error, cannot_use_min, pc) ++ (Theta := oldTheta)
    }


    P match {
      case sil.Seqn(ss, _) => ss.map(handleProofScript(_, error, cannot_use_min, pc))
      case sil.If(cond, s1, s2) =>
        rstate = stateModule.freshTempState("temp")
        val tcond = expModule.translateExp(cond)
        stateModule.replaceState(rstate._2) // go back to the original state
        Seqn(Seq(handleProofScript(s1, error, cannot_use_min, pc && tcond), handleProofScript(s2, error, cannot_use_min, pc && UnExp(Not, tcond))))
      case sil.Assert(a) =>
        val restore: Exp = Forall(Seq(decl, obj, field), Seq(Trigger(M_temp_h_xf), Trigger(M_h_xf), Trigger(Theta_h_xf), Trigger(oldTheta_h_xf)),
          (M_temp_h_xf === (M_h_xf + (Theta_h_xf - oldTheta_h_xf))))
        (oldTheta := Theta) ++
        exhaleRHS(a, error, cannot_use_min, pc) ++
        Havoc(M_temp) ++ Assume(restore) ++ (M := M_temp) ++ (Theta := oldTheta)

      case sil.Fold(acc@sil.PredicateAccessPredicate(pa@sil.PredicateAccess(_, _), perm)) =>
        // TODO: Check definedness acc, check definedness perm
        val body: sil.Exp = sil.utility.Permissions.multiplyExpByPerm(acc.loc.predicateBody(verifier.program, mainModule.env.allDefinedNames(program)).get, acc.perm)

        exhaleRestore(body) ++ inhaleLHS(acc, pc)

      case sil.Unfold(acc@sil.PredicateAccessPredicate(pa@sil.PredicateAccess(_, _), perm)) =>
        // TODO: Check definedness acc, check definedness perm
        val body: sil.Exp = sil.utility.Permissions.multiplyExpByPerm(acc.loc.predicateBody(verifier.program, mainModule.env.allDefinedNames(program)).get, acc.perm)
        exhaleRestore(acc) ++ inhaleLHS(body, pc)

      case a@sil.Apply(w@sil.MagicWand(left, right)) =>
        exhaleRestore(w) ++ exhaleRestore(left) ++ inhaleLHS(removeLabelLHS(right), pc)

    }
  }

  def removeLabelLHS(A: sil.Exp): sil.Exp = {
    A.transform({
      case sil.LabelledOld(e, s) if s == "lhs" => e
    })
  }

  override def translatePackage(p: sil.Package, error: PartialVerificationError, statesStack: List[Any] = null, allStateAssms: Exp = TrueLit(), inWand: Boolean = false):Stmt = {

  p match {
      case pa@sil.Package(wand, proofScript: sil.Seqn) =>

        if(verifier.wandType != 0) {
          val lhsIsNotBinary = FractionsDetector.potentiallyHasFractions(wand.left) || FractionsDetector.potentiallyHasFractions(proofScript)
          val somePredicatePerm = FractionsDetector.hasPredicatePermission(wand) || FractionsDetector.hasPredicatePermission(proofScript)
          if(lhsIsNotBinary && somePredicatePerm) {
            //println("WAND_MSG_2: Nonbinary LHS and predicates")
            verifier.wandsMayNotBeCombinableNonBinaryLHS()
          }
        }

        val cannot_use_min = accessesDetGaurav(wand)
        //println("Checking wand", wand, cannot_use_min)
        wand match {
          case w@sil.MagicWand(left, right) =>
           // saving the old variables as they would be overwritten in the case of nested magic wands

            // TODO: Assert that left and right are self-framing

            val addWand = inhaleModule.inhale(Seq((w, error)), statesStack, inWand)

            val stmt = initPackage() ++ inhaleLHS(left) ++ initTheta() ++ handleProofScript(proofScript, error, cannot_use_min) ++ exhaleRHS(right, error, cannot_use_min)

           val retStmt = stmt ++ addWand ++ heapModule.endExhale
            retStmt

          case _ => sys.error("Package only defined for wands.")
        }
    }
  }

  // -------------------------------------------------------------
  // TD: End of sound package
  // -------------------------------------------------------------




  def old_translatePackage(p: sil.Package, error: PartialVerificationError, statesStack: List[Any] = null, allStateAssms: Exp = TrueLit(), inWand: Boolean = false):Stmt = {
    val proofScript = p.proofScript
    p match {
      case pa@sil.Package(wand, proof) =>
        wand match {
          case w@sil.MagicWand(left,right) =>
            // saving the old variables as they would be overwritten in the case of nested magic wands
            var oldW = currentWand
            val oldOps = OPS

            currentWand = w
            val addWand = inhaleModule.inhale(Seq((w, error)), statesStack, inWand)

            val currentState = stateModule.state

            val PackageSetup(opsState, usedState, initStmt) = packageInit(wand, None, error)
            val curStateBool = LocalVar(Identifier(names.createUniqueIdentifier("boolCur"))(transferNamespace),Bool)

            val newStatesStack =
              if(inWand)
                statesStack.asInstanceOf[List[StateRep]]
              else
                StateRep(currentState, curStateBool) :: Nil


            val locals = proofScript.scopedDecls.collect {case l: sil.LocalVarDecl => l}
            locals map (v => mainModule.env.define(v.localVar)) // add local variables to environment

            val stmt = initStmt++(curStateBool := TrueLit()) ++
              MaybeCommentBlock("Assumptions about local variables", locals map (a => mainModule.allAssumptionsAboutValue(a.typ, mainModule.translateLocalVarDecl(a), true))) ++
              translatePackageBody(newStatesStack, opsState, proofScript.ss , right, opsState.boolVar && allStateAssms, error)

            locals map (v => mainModule.env.undefine(v.localVar)) // remove local variables from environment

            // updating the global variables to finish executing the wand (removing it from active wands and resetting variables to old magic wand in case of nested package stmts)
            OPS = oldOps
            stateModule.replaceState(currentState)
            activeWandsStack = activeWandsStack.dropRight(1)
            nestingDepth -= 1
            val retStmt = stmt ++ addWand
            currentWand = oldW
            retStmt

          case _ => sys.error("Package only defined for wands.")
        }
    }
  }

  // This method translates the package statement body by calling 'translateInWandStatement' and then translates the rhs by calling 'exec'
  def translatePackageBody(states: List[StateRep], ops: StateRep, proofScript: Seq[sil.Stmt], right: sil.Exp, allStateAssms: Exp, error: PartialVerificationError):Stmt = {
    (proofScript map(s => translateInWandStatement(states, ops, s, allStateAssms)))++
    exec(states,ops, right, allStateAssms, error)
  }

  /**
    * This translates a statement 's' inside a packages statement. It does the following:-
    * 1) Sets the UnionState (in which expressions are going to be evaluated) to the ops-state.
    * 2) Sets the current state to the ops-state.
    * 3) calls the normal stmtModule.translateStmt passing the right parameters to indicate that 's' is inside a package statement.
    */
  def translateInWandStatement(states: List[StateRep], ops: StateRep, s: sil.Stmt, allStateAssms: Exp): Stmt =
  {
    UNIONState = OPS
    stateModule.replaceState(OPS.state)
    If(allStateAssms, stmtModule.translateStmt(s, ops::states, allStateAssms , true), Statements.EmptyStmt)
  }


  /**
   * this function translates the right-hand side of the magic wand
   * @param states  stack of hypothetical states
   * @param ops state we're constructing, the state should always be set to this state right before a call to exec
   *            and right after the exec call is finished
   * @param e expression in wand which we are regarding (the right-hand side of the wand)
   * @param allStateAssms the conjunction of all boolean variables corresponding to the states on the stack
   * @return
   */
  def exec(states: List[StateRep], ops: StateRep, e:sil.Exp, allStateAssms: Exp, mainError: PartialVerificationError):Stmt = {
    e match {
      case sil.Let(letVarDecl, exp, body) =>
        val StateRep(_,bOps) = ops
        val translatedExp = expModule.translateExp(exp) // expression to bind "v" to, evaluated in ops state
        val v = mainModule.env.makeUniquelyNamed(letVarDecl) // choose a fresh "v" binder
        mainModule.env.define(v.localVar)
        val instantiatedExp = Expressions.instantiateVariables(body,Seq(letVarDecl),Seq(v.localVar), mainModule.env.allDefinedNames(program)) //instantiate bound variable

        val stmt =
          (bOps := bOps && (mainModule.env.get(v.localVar) === translatedExp) ) ++
          //GP: maybe it may make more sense to use an assignment here instead of adding the equality as an assumption, but since right now we use all assumptions
          //of states on the state + state ops to assert expressions, it should work
          exec(states,ops,instantiatedExp,allStateAssms, mainError)

        mainModule.env.undefine(v.localVar)

        stmt
      case _ =>
        //no ghost operation
        UNIONState = OPS
        val StateSetup(usedState, initStmt) = createAndSetState(None)
        tempCurState = usedState
        Comment("Translating exec of non-ghost operation" + e.toString()) ++
        initStmt ++  exhaleExt(ops :: states, usedState,e,ops.boolVar&&allStateAssms, RHS = true, mainError)
    }
  }

override def exhaleExt(statesObj: List[Any], usedObj:Any, e: sil.Exp, allStateAssms: Exp, RHS: Boolean = false, error: PartialVerificationError, havocHeap: Boolean):Stmt = {
  Comment("exhale_ext of " + e.toString())
  val states = statesObj.asInstanceOf[List[StateRep]]
  val used = usedObj.asInstanceOf[StateRep]
  e match {
    case _: sil.AccessPredicate => transferMain(states, used, e, allStateAssms, error, havocHeap)
    case sil.And(e1,e2) =>
      exhaleExt(states, used, e1,allStateAssms, RHS, error, havocHeap) :: exhaleExt(states,used,e2,allStateAssms, RHS, error, havocHeap) :: Nil
    case sil.Implies(e1,e2) =>
      If(allStateAssms,
        If(expModule.translateExpInWand(e1), exhaleExt(states,used,e2,allStateAssms, RHS, error, havocHeap),Statements.EmptyStmt),
        Statements.EmptyStmt)
    case sil.CondExp(c,e1,e2) =>
      If(allStateAssms,
        If(expModule.translateExpInWand(c), exhaleExt(states,used,e1,allStateAssms, RHS, error, havocHeap), exhaleExt(states,used,e2,allStateAssms, RHS, error, havocHeap)),
        Statements.EmptyStmt)
    case _ => exhaleExtExp(states,used,e,allStateAssms, RHS, error)
  }
}

def exhaleExtExp(states: List[StateRep], used:StateRep, e: sil.Exp,allStateAssms:Exp, RHS: Boolean = false, mainError: PartialVerificationError):Stmt = {
  if(e.isPure) {
    If(allStateAssms&&used.boolVar,expModule.checkDefinedness(e,mainError, insidePackageStmt = true),Statements.EmptyStmt) ++
      Assert((allStateAssms&&used.boolVar) ==> expModule.translateExpInWand(e), mainError.dueTo(reasons.AssertionFalse(e)))

  } else {
    sys.error("impure expression not caught in exhaleExt")
  }
}

/*
 * Precondition: current state is set to the used state
  */
def transferMain(states: List[StateRep], used:StateRep, e: sil.Exp, allStateAssms: Exp, mainError: PartialVerificationError, havocHeap: Boolean = true):Stmt = {
  //store the permission to be transferred into a separate variable
  val permAmount =
    e   match {
      case sil.MagicWand(left, right) => boogieFullPerm
      case p@sil.AccessPredicate(loc, perm) => permModule.translatePerm(perm)
      case _ => sys.error("only transfer of access predicates and magic wands supported")
    }

  val (transferEntity, initStmt)  = setupTransferableEntity(e, transferAmountLocal)
  val initPermVars = (neededLocal := permAmount) ++
    (initNeededLocal := permModule.currentPermission(transferEntity.rcv, transferEntity.loc)+neededLocal)


  val positivePerm = Assert(neededLocal >= RealLit(0), mainError.dueTo(reasons.NegativePermission(e)))

  val definedness =
    MaybeCommentBlock("checking if access predicate defined in used state",
    If(allStateAssms&&used.boolVar,expModule.checkDefinedness(e, mainError, insidePackageStmt = true),Statements.EmptyStmt))

  val transferRest = transferAcc(states,used, transferEntity,allStateAssms, mainError, havocHeap)
  val stmt = definedness++ initStmt /*++ nullCheck*/ ++ initPermVars ++  positivePerm ++ transferRest

  val unionStmt = updateUnion()

  MaybeCommentBlock("Transfer of " + e.toString(), stmt ++ unionStmt)

}

/*
 * Precondition: current state is set to the used state
  */
private def transferAcc(states: List[StateRep], used:StateRep, e: TransferableEntity, allStateAssms: Exp, mainError: PartialVerificationError, havocHeap: Boolean = true):Stmt = {
  states match {
    case (top :: xs) =>
      //Compute all values needed from top state
      stateModule.replaceState(top.state)
      val isOriginalState: Boolean = xs.isEmpty

      val topHeap = heapModule.currentHeap
      val equateLHS:Option[Exp] = e match {
        case TransferableAccessPred(rcv,loc,_,_) => Some(heapModule.translateLocationAccess(rcv,loc))
        case _ => None
      }

      val definednessTop:Stmt = (boolTransferTop := TrueLit()) ++
        (generateStmtCheck(components flatMap (_.transferValid(e)), boolTransferTop))
      val minStmt = If(neededLocal <= curpermLocal,
        transferAmountLocal := neededLocal, transferAmountLocal := curpermLocal)

      val nofractionsStmt = (transferAmountLocal := RealLit(1.0))

      val curPermTop = permModule.currentPermission(e.rcv, e.loc)
      val removeFromTop = heapModule.beginExhale ++
        (components flatMap (_.transferRemove(e,used.boolVar))) ++
         {if(top != OPS){ // Sets the transferred field in secondary mask of the wand to true ,eg., Heap[null, w#sm][x, f] := true
           heapModule.addPermissionToWMask(getWandFtSmRepresentation(currentWand, 1), e.originalSILExp)
          }else{
           Statements.EmptyStmt
          }
         }
        ( //if original state then don't need to guard assumptions
          if(isOriginalState) {
            heapModule.endExhale ++
              stateModule.assumeGoodState
          } else if(top != OPS || havocHeap){
            // We only havoc the heap from which we remove the permission only if:
            //      The translated statement requires havocing the heap (fold)
            //      OR if the state from which the permission is removed is not OPS state
              exchangeAssumesWithBoolean(heapModule.endExhale, top.boolVar) ++
              (top.boolVar := top.boolVar && stateModule.currentGoodState)
          }else
            top.boolVar := top.boolVar && stateModule.currentGoodState)

      /*GP: need to formally prove that these two last statements are sound (since they are not
       *explicitily accumulated in the boolean variable */

      //computed all values needed from used state
      stateModule.replaceState(used.state)

      val addToUsed = (components flatMap (_.transferAdd(e,used.boolVar))) ++
        (used.boolVar := used.boolVar&&stateModule.currentGoodState)

      val equateStmt:Stmt = e match {
        case TransferableFieldAccessPred(rcv,loc,_,_) => (used.boolVar := used.boolVar&&(equateLHS.get === heapModule.translateLocationAccess(rcv,loc)))
        case TransferablePredAccessPred(rcv,loc,_,_) =>
          val (tempMask, initTMaskStmt) = permModule.tempInitMask(rcv,loc)
          initTMaskStmt ++
            (used.boolVar := used.boolVar&&heapModule.identicalOnKnownLocations(topHeap,tempMask))
        case _ => Nil
      }

      //transfer from top to used state
      MaybeCommentBlock("transfer code for top state of stack",
        Comment("accumulate constraints which need to be satisfied for transfer to occur") ++ definednessTop ++
          Comment("actual code for the transfer from current state on stack") ++
          If( (allStateAssms&&used.boolVar) && boolTransferTop && neededLocal > boogieNoPerm,
            (curpermLocal := curPermTop) ++
              minStmt ++
              If(transferAmountLocal > boogieNoPerm,
                (neededLocal := neededLocal - transferAmountLocal) ++
                  addToUsed ++
                  equateStmt ++
                  removeFromTop,

                Nil),

            Nil
          )
      ) ++ transferAcc(xs,used,e,allStateAssms, mainError, havocHeap) //recurse over rest of states
    case Nil =>
      val curPermUsed = permModule.currentPermission(e.rcv,e.loc)
      Assert((allStateAssms&&used.boolVar) ==> (neededLocal === boogieNoPerm && curPermUsed === initNeededLocal),
        e.transferError(mainError))
    /**
     * actually only curPermUsed === permLocal would be sufficient if the transfer is written correctly, since
     * the two conjuncts should be equivalent in theory, but to be safe we ask for the conjunction to hold
     * in case of bugs
     * */
  }
}


  override def createAndSetState(initBool:Option[Exp],usedString:String = "Used",setToNew:Boolean=true,
                                 init:Boolean=true):StateSetup = {
    /**create a new boolean variable under which all assumptions belonging to the package are made
      *(which makes sure that the assumptions won't be part of the main state after the package)
      */

    val b = LocalVar(Identifier(names.createUniqueIdentifier("b"))(transferNamespace), Bool)

    val boolStmt =
      initBool match {
        case Some(boolExpr) => (b := boolExpr)
        case _ => Statements.EmptyStmt
      }


    //state which is used to check if the wand holds
    val usedName = names.createUniqueIdentifier(usedString)

    val (usedStmt, currentState) = stateModule.freshEmptyState(usedName,init)
    val usedState = stateModule.state

    val goodState = exchangeAssumesWithBoolean(stateModule.assumeGoodState, b)

    /**DEFINE STATES END **/
    if(!setToNew) {
      stateModule.replaceState(currentState)
    }

    StateSetup(StateRep(usedState,b),usedStmt++boolStmt++goodState)
  }


  //create a state Result which is the "sum" of the current and the input state (stateOtherO)
  override def createAndSetSumState(stateOtherO: Any ,boolOther:Exp,boolCur:Exp):StateSetup = {
    // get heap and mask from other state
    val stateOther = stateOtherO.asInstanceOf[StateSnapshot]
    val currentState = stateModule.state
    stateModule.replaceState(stateOther)
    val heapOther = heapModule.currentHeap
    val maskOther = permModule.currentMask
    stateModule.replaceState(currentState)

    createAndSetSumState(heapOther, maskOther, boolOther, boolCur)
  }

  /**
    * only heap and mask of one summand state, while current state will be used as second summand
    * @param heapOther heap representation of  summand state
    * @param maskOther mask represenstation of  summand state
    * @param boolOther bool containing facts for summand state
    * @param boolCur bool containing facts for current state
    * @return
    */
  private def createAndSetSumState(heapOther: Seq[Exp], maskOther: Seq[Exp],boolOther:Exp,boolCur:Exp):StateSetup = {
    /*create a state Result which is the "sum" of the current  and Other states, i.e.:
  *1) at each heap location o.f the permission of the Result state is the sum of the permissions
  * for o.f in the current and Other state
  * 2) if the current state has some positive nonzero amount of permission for heap location o.f then the heap values for
  * o.f in the current state and Result state are the same
  * 3) point 2) also holds for the Other state in place of the current state
  *
  * Note: the boolean for the Result state is initialized to the conjunction of the booleans of the current state
   *  and the other state and used (since all facts of each state is transferred)
  */
    val curHeap = heapModule.currentHeap
    val curMask = permModule.currentMask

    val StateSetup(resultState, initStmtResult) =
      createAndSetState(Some(boolOther && boolCur), "Result", true, false)

    val boolRes = resultState.boolVar
    val sumStates = (boolRes := boolRes && permModule.sumMask(maskOther, curMask))
    val equateKnownValues = (boolRes := boolRes && heapModule.identicalOnKnownLocations(heapOther, maskOther) &&
      heapModule.identicalOnKnownLocations(curHeap, curMask))
    val goodState = exchangeAssumesWithBoolean(stateModule.assumeGoodState, boolRes)
    val initStmt =CommentBlock("Creating state which is the sum of the two previously built up states",
      initStmtResult ++ sumStates ++ equateKnownValues ++ goodState)

    StateSetup(resultState, initStmt)

  }


/**
 * @param e expression to be transferred
 * @param permTransfer  expression which denotes the permission amount to be transferred
 * @return a TransferableEntity which can be handled by  all TransferComponents as well as a stmt that needs to be
 *         in the Boogie program before the any translation concerning the TransferableEntity can be used
 */
private def setupTransferableEntity(e: sil.Exp, permTransfer: Exp):(TransferableEntity,Stmt) = {
  e match {
    case fa@sil.FieldAccessPredicate(loc, _) =>
      val assignStmt = rcvLocal := expModule.translateExpInWand(loc.rcv)
      val evalLoc = heapModule.translateLocation(loc)
      (TransferableFieldAccessPred(rcvLocal, evalLoc, permTransfer,fa), assignStmt)

    case p@sil.PredicateAccessPredicate(loc, _) =>
      val localsStmt: Seq[(LocalVar, Stmt)] = (for (arg <- loc.args) yield {
        val v = LocalVar(Identifier(names.createUniqueIdentifier("arg"))(transferNamespace),
          typeModule.translateType(arg.typ))
        (v, v := expModule.translateExpInWand(arg))
      })
      val (locals, assignStmt) = localsStmt.unzip
      val predTransformed = heapModule.translateLocation(p.loc.loc(verifier.program), locals)
      (TransferablePredAccessPred(heapModule.translateNull, predTransformed, permTransfer,p), assignStmt)

    case w:sil.MagicWand =>
      val wandRep = getWandRepresentation(w)
      //GP: maybe should store holes of wand first in local variables
      (TransferableWand(heapModule.translateNull, wandRep, permTransfer, w),Nil)
  }
}

override def exchangeAssumesWithBoolean(stmt: Stmt,boolVar: LocalVar):Stmt = {
  stmt match {
    case Assume(exp) =>
      boolVar := (boolVar && viper.carbon.boogie.PrettyPrinter.quantifyOverFreeTypeVars(exp))
    case Seqn(statements) =>
      Seqn(statements.map(s => exchangeAssumesWithBoolean(s, boolVar)))
    case If(c, thn, els) =>
      If(c, exchangeAssumesWithBoolean(thn, boolVar), exchangeAssumesWithBoolean(els, boolVar))
    case NondetIf(thn, els) =>
      NondetIf(exchangeAssumesWithBoolean(thn, boolVar))
    case CommentBlock(comment, s) =>
      CommentBlock(comment, exchangeAssumesWithBoolean(s, boolVar))
    case s => s
  }
}

  /**
    * Transforms all asserts and if conditions in the statement 'stmt' as following:
    * Assert e is transformed to: Assert (boolVar => e)
    * if(c) {...} else {...} is transformed to: if(boolVar){ if(c) {...} else {...} }
    * where boolVar represents the boolean expression carrying assumptions about some states.
    */
  def modifyAssert(stmt: Stmt,boolVar: LocalVar):Stmt = {
    stmt match {
      case Assert(exp, error) => Assert(boolVar ==> exp, error)
      case Seqn(statements) =>
        Seqn(statements.map(s => modifyAssert(s, boolVar)))
      case If(c,thn,els) =>
        If(boolVar, If(c,modifyAssert(thn,boolVar),modifyAssert(els,boolVar)), Statements.EmptyStmt)
      case NondetIf(thn,els) =>
        NondetIf(modifyAssert(thn,boolVar))
      case CommentBlock(comment,s) =>
        CommentBlock(comment, modifyAssert(s,boolVar))
      case s => s
    }
  }

/*
 * Let the argument be a sequence [(s1,e1),(s2,e2)].
 * Then the function returns:
 * (s1; b := b&&e1; s2; b := b&&e2)
 */
def generateStmtCheck(x: Seq[(Stmt,Exp)], boolVar:LocalVar):Stmt = {
  (x map (y => y._1 ++ (boolVar := boolVar&&y._2))).flatten
}

/*
 *this class is used to group the local boogie variables used for the transfer which are potentially different
 * for each transfer (in contrast to "neededLocal" which ist declared as a constant)
 */
case class TransferBoogieVars(transferAmount: LocalVar, boolVar: LocalVar)

/*
 * is used to store relevant blocks needed to start an exec or package
 */
case class PackageSetup(hypState: StateRep, usedState: StateRep, initStmt: Stmt)

  /**
    * This method update 'wandModule.UNIONState' to be the union of the 'current' state and 'wandModule.OPS' state
    * The 'wandModule.UNIONState' is used in evaluating expressions during a package statement.
    */
  override def updateUnion(): Stmt = {
    val oldCurState = stateModule.state
    stateModule.replaceState(tempCurState.asInstanceOf[StateRep].state)
    val StateSetup(resultState, unionStatement) = createAndSetSumState(OPS.state, OPS.boolVar, tempCurState.asInstanceOf[StateRep].boolVar)
    UNIONState = resultState
    stateModule.replaceState(oldCurState)
    unionStatement ++ (OPS.boolVar := OPS.boolVar && resultState.boolVar)
  }

  /**
    * Wraps all statements inside package statement inside If condition depending on the state variables.
    */
  override  def handleStmt(s: sil.Stmt, statesStack: List[Any] = null, allStateAssms: Exp = TrueLit(), inWand: Boolean = false): (Seqn => Seqn) = {
    if(wandModule.nestingDepth > 0) // if 's' is inside a package statement
      stmt => If(allStateAssms, modifyAssert(stmt, OPS.boolVar), Statements.EmptyStmt)::Nil
    else
      stmt => stmt
  }


  override def start(): Unit = {
    stmtModule.register(this, before = Seq(verifier.heapModule,verifier.permModule, verifier.stmtModule)) // checks for field assignment should be made before the assignment itself
    expModule.register(this)
  }


  // Prepares translating a statement during a package statement by setting the unionState to opsState and setting opsState as the current state.
  override def translatingStmtsInWandInit(): Unit ={
    UNIONState = OPS
    stateModule.replaceState(OPS.state)
  }

  //  =============================================== Applying wands ===============================================
  override def translateApply(app: sil.Apply, error: PartialVerificationError, statesStack: List[Any] = null, allStateAssms: Exp = TrueLit(), inWand: Boolean = false):Stmt = {
    app.exp match {
      case w@sil.MagicWand(left,right) => applyWand(w,error, statesStack, allStateAssms, inWand)
      case _ => Nil
    }
  }

  def applyWand(w: sil.MagicWand, error: PartialVerificationError, statesStack: List[Any] = null, allStateAssms: Exp = TrueLit(), inWand: Boolean = false):Stmt = {
    /** we first exhale without havocing heap locations to avoid the incompleteness issue which would otherwise
      * occur when the left and right hand side mention common heap locations.
      */
    val lhsID = wandModule.getNewLhsID() // identifier for the lhs of the wand to be referred to later when 'old(lhs)' is used
    val defineLHS = stmtModule.translateStmt(sil.Label("lhs"+lhsID, Nil)(w.pos, w.info))
    wandModule.pushToActiveWandsStack(lhsID)

    val ret = CommentBlock("check if wand is held and remove an instance",exhaleModule.exhale((w, error), false, insidePackageStmt = inWand, statesStackForPackageStmt = statesStack)) ++
      (if(inWand) exchangeAssumesWithBoolean(stateModule.assumeGoodState, OPS.boolVar) else stateModule.assumeGoodState) ++
      CommentBlock("check if LHS holds and remove permissions ", exhaleModule.exhale((w.left, error), false, insidePackageStmt = inWand, statesStackForPackageStmt = statesStack)) ++
      (if(inWand) exchangeAssumesWithBoolean(stateModule.assumeGoodState, OPS.boolVar) else stateModule.assumeGoodState) ++
      CommentBlock("inhale the RHS of the wand",inhaleModule.inhale(Seq((w.right, error)), statesStackForPackageStmt = statesStack, insidePackageStmt = inWand)) ++
      heapModule.beginExhale ++ heapModule.endExhale ++
      (if(inWand) exchangeAssumesWithBoolean(stateModule.assumeGoodState, OPS.boolVar) else stateModule.assumeGoodState)
    //GP: using beginExhale, endExhale works now, but isn't intuitive, maybe should duplicate code to avoid this breaking
    //in the future when beginExhale and endExhale's implementations are changed
    popFromActiveWandsStack()
    ret
  }
  //  =============================================== End of Applying wands ===============================================


  // =============================================== Handling 'old(lhs)' ===============================================

  override def getActiveLhs(): Int = {
    activeWandsStack.last
  }

  override def getNewLhsID():Int ={
    lhsID = lhsID+1
    lhsID
  }

  override def pushToActiveWandsStack(lhsNum: Int): Unit ={
    activeWandsStack = activeWandsStack:+lhsNum
  }

  override def popFromActiveWandsStack(): Unit ={
    activeWandsStack = activeWandsStack.dropRight(1)
  }
  // ============================================ End of Handling 'old(lhs)' ===========================================


  // =============================================== Checking definedness ===============================================

  private var tmpStateId = -1
  /**
    * Checking definedness for applying statement
    */
  override def partialCheckDefinedness(e: sil.Exp, error: PartialVerificationError, makeChecks: Boolean): (() => Stmt, () => Stmt) = {
    e match {
      case a@sil.Applying(wand, exp) =>
        tmpStateId += 1
        val tmpStateName = if (tmpStateId == 0) "Applying" else s"Applying$tmpStateId"
        val (stmt, state) = stateModule.freshTempState(tmpStateName)
        def before() = {
          stmt ++ applyWand(wand, error, inWand = false)
        }
        def after() = {
          tmpStateId -= 1
          stateModule.replaceState(state)
          Nil
        }
        (before _, after _)
      case _ => (() => simplePartialCheckDefinedness(e, error, makeChecks), () => Nil)
    }
  }

  // =========================================== End Of Checking Definedness ============================================

  // =========================================== Getters ============================================
  override def getCurOpsBoolvar(): LocalVar = {
    OPS.boolVar
  }

  override def getOps(): StateRep = {
    OPS
  }
  // =========================================== End Of Getters ============================================
}
