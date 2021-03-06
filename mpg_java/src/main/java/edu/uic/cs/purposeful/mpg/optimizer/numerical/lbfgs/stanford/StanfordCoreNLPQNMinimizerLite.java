package edu.uic.cs.purposeful.mpg.optimizer.numerical.lbfgs.stanford;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;

import edu.uic.cs.purposeful.mpg.MPGConfig;
import edu.uic.cs.purposeful.mpg.optimizer.numerical.IterationCallback;
import edu.uic.cs.purposeful.mpg.optimizer.numerical.objective.MinimizationObjectiveFunction;

/**
 *
 * An implementation of L-BFGS for Quasi Newton unconstrained minimization. Also now has support for
 * OWL-QN (Orthant-Wise Limited memory Quasi Newton) for L1 regularization.
 *
 * The general outline of the algorithm is taken from: <blockquote> <i>Numerical Optimization</i>
 * (second edition) 2006 Jorge Nocedal and Stephen J. Wright </blockquote> A variety of different
 * options are available.
 *
 * <h3>LINESEARCHES</h3>
 *
 * BACKTRACKING: This routine simply starts with a guess for step size of 1. If the step size
 * doesn't supply a sufficient decrease in the function value the step is updated through step =
 * 0.1*step. This method is certainly simpler, but doesn't allow for an increase in step size, and
 * isn't well suited for Quasi Newton methods.
 *
 * MINPACK: This routine is based off of the implementation used in MINPACK. This routine finds a
 * point satisfying the Wolfe conditions, which state that a point must have a sufficiently smaller
 * function value, and a gradient of smaller magnitude. This provides enough to prove theoretically
 * quadratic convergence. In order to find such a point the linesearch first finds an interval which
 * must contain a satisfying point, and then progressively reduces that interval all using cubic or
 * quadratic interpolation.
 *
 * SCALING: L-BFGS allows the initial guess at the hessian to be updated at each step. Standard BFGS
 * does this by approximating the hessian as a scaled identity matrix. To use this method set the
 * scaleOpt to SCALAR. A better way of approximate the hessian is by using a scaling diagonal
 * matrix. The diagonal can then be updated as more information comes in. This method can be used by
 * setting scaleOpt to DIAGONAL.
 *
 * CONVERGENCE: Previously convergence was gauged by looking at the average decrease per step
 * dividing that by the current value and terminating when that value because smaller than TOL. This
 * method fails when the function value approaches zero, so two other convergence criteria are used.
 * The first stores the initial gradient norm |g0|, then terminates when the new gradient norm, |g|
 * is sufficiently smaller: i.e., |g| &lt; eps*|g0| the second checks if |g| &lt; eps*max( 1 , |x| )
 * which is essentially checking to see if the gradient is numerically zero. Another convergence
 * criteria is added where termination is triggered if no improvements are observed after X (set by
 * terminateOnEvalImprovementNumOfEpoch) iterations over some validation test set as evaluated by
 * Evaluator
 *
 * Each of these convergence criteria can be turned on or off by setting the flags: <blockquote>
 * <code>
 * private boolean useAveImprovement = true;
 * private boolean useRelativeNorm = true;
 * private boolean useNumericalZero = true;
 * private boolean useEvalImprovement = false;
 * </code></blockquote>
 *
 * To use the QNMinimizer first construct it using <blockquote><code>
 * QNMinimizer qn = new QNMinimizer(mem, true)
 * </code></blockquote> mem - the number of previous estimate vector pairs to store, generally 15 is
 * plenty. true - this tells the QN to use the MINPACK linesearch with DIAGONAL scaling. false would
 * lead to the use of the criteria used in the old QNMinimizer class.
 *
 * Then call: <blockquote><code>
 * qn.minimize(dfunction,convergenceTolerance,initialGuess,maxFunctionEvaluations);
 * </code></blockquote>
 *
 * @author akleeman
 */

public class StanfordCoreNLPQNMinimizerLite {
  private static final Logger LOGGER = Logger.getLogger(StanfordCoreNLPQNMinimizerLite.class);

  private int fevals = 0; // the number of function evaluations
  private int maxFevals = -1;
  private int mem = 10; // the number of s,y pairs to retain for BFGS
  private int its = 0; // the number of iterations through the main do-while loop of L-BFGS's
                       // minimize()
  private boolean quiet;
  private static final NumberFormat nf = new DecimalFormat("0.000E0");
  private static final NumberFormat nfsec = new DecimalFormat("0.00"); // for times
  private static final double ftol = 1e-4; // Linesearch parameters
  private double gtol = 0.9;
  private static final double aMin = 1e-12; // Min step size
  private static final double aMax = 1e12; // Max step size
  private static final double p66 = 0.66; // used to check getting more than 2/3 of width
                                          // improvement
  private static final double p5 = 0.5; // Some other magic constant
  private static final int a = 0; // used as array index
  private static final int f = 1; // used as array index
  private static final int g = 2; // used as array index
  private boolean success = false;
  private boolean bracketed = false; // used for linesearch

  private boolean useMaxItr = false;
  private int maxItr = 0;

  private static enum eState {
    TERMINATE_RELATIVENORM, TERMINATE_GRADNORM, TERMINATE_AVERAGEIMPROVE, CONTINUE, TERMINATE_MAXITR
  }

  private static enum eScaling {
    DIAGONAL, SCALAR
  }

  private eScaling scaleOpt = eScaling.DIAGONAL;
  private eState state = eState.CONTINUE;

  public StanfordCoreNLPQNMinimizerLite(int m) {
    mem = m;
  }

  public eState getState() {
    return state;
  }

  public void terminateOnMaxItr(int maxItr) {
    if (maxItr > 0) {
      useMaxItr = true;
      this.maxItr = maxItr;
    }
  }

  public boolean wasSuccessful() {
    return success;
  }

  public void shutUp() {
    this.quiet = true;
  }

  private static class SurpriseConvergence extends Throwable {
    private static final long serialVersionUID = 4290178321643529559L;

    private SurpriseConvergence(String s) {
      super(s);
    }
  }

  private static class MaxEvaluationsExceeded extends Throwable {
    private static final long serialVersionUID = 8044806163343218660L;

    private MaxEvaluationsExceeded(String s) {
      super(s);
    }
  }

  /**
   * The Record class is used to collect information about the function value over a series of
   * iterations. This information is used to determine convergence, and to (attempt to) ensure
   * numerical errors are not an issue. It can also be used for plotting the results of the
   * optimization routine.
   *
   * @author akleeman
   */
  private class Record {
    // convergence options.
    // have average difference like before
    // zero gradient.

    // for convergence test
    private final List<Double> values = new ArrayList<>();
    private List<Double> gNorms = new ArrayList<>();
    // List<Double> xNorms = new ArrayList<Double>();
    private final List<Integer> funcEvals = new ArrayList<>();
    private final List<Double> time = new ArrayList<>();
    // gNormInit: This makes it so that if for some reason
    // you try and divide by the initial norm before it's been
    // initialized you don't get a NAN but you will also never
    // get false convergence.
    private double gNormInit = Double.MIN_VALUE;
    private double relativeTOL = 1e-8;
    private double TOL = MPGConfig.LBFGS_TERMINATE_VALUE_TOLERANCE;
    private double EPS = MPGConfig.LBFGS_TERMINATE_GRADIENT_TOLERANCE;
    private long startTime;
    private double gNormLast; // This is used for convergence.
    private double[] xLast;
    private int maxSize = 100; // This will control the number of func values /
                               // gradients to retain.
    private boolean memoryConscious = true;

    /*
     * Initialize the class, this starts the timer, and initiates the gradient norm for use with
     * convergence.
     */
    private void start(double val, double[] grad, double[] x) {
      startTime = System.currentTimeMillis();
      gNormInit = ArrayMath.norm(grad);
      xLast = x;
    }

    private void add(double val, double[] grad, double[] x, int fevals) {

      if (!memoryConscious) {
        if (gNorms.size() > maxSize) {
          gNorms.remove(0);
        }
        if (time.size() > maxSize) {
          time.remove(0);
        }
        if (funcEvals.size() > maxSize) {
          funcEvals.remove(0);
        }
        gNorms.add(gNormLast);
        time.add(howLong());
        funcEvals.add(fevals);
      } else {
        maxSize = 10;
      }

      gNormLast = ArrayMath.norm(grad);
      if (values.size() > maxSize) {
        values.remove(0);
      }

      values.add(val);

      say(nf.format(val) + " " + nfsec.format(howLong()) + "s");

      xLast = x;
    }

    /**
     * This function checks for convergence through first order optimality, numerical convergence
     * (i.e., zero numerical gradient), and also by checking the average improvement.
     *
     * @return A value of the enumeration type
     *         <p>
     *         eState
     *         </p>
     *         which tells the state of the optimization routine indicating whether the routine
     *         should terminate, and if so why.
     */
    private eState toContinue() {
      double relNorm = gNormLast / gNormInit;
      int size = values.size();
      double newestVal = values.get(size - 1);
      double previousVal = (size >= 10 ? values.get(size - 10) : values.get(0));
      double averageImprovement = (previousVal - newestVal) / (size >= 10 ? 10 : size);

      if (useMaxItr && its >= maxItr)
        return eState.TERMINATE_MAXITR;

      // This is used to be able to reproduce results that were trained on the
      // QNMinimizer before
      // convergence criteria was updated.
      if (size > 5 && Math.abs(averageImprovement / newestVal) < TOL) {
        return eState.TERMINATE_AVERAGEIMPROVE;
      }

      // Check to see if the gradient is sufficiently small
      if (relNorm <= relativeTOL) {
        return eState.TERMINATE_RELATIVENORM;
      }

      // This checks if the gradient is sufficiently small compared to x that
      // it is treated as zero.
      double xnorm1 = Math.max(1.0, ArrayMath.norm_1(xLast));
      if (gNormLast < EPS * xnorm1) {
        // |g| < |x|_1
        // First we do the one norm, because that's easiest, and always bigger.
        double xnorm = Math.max(1.0, ArrayMath.norm(xLast));

        if (MPGConfig.SHOW_RUNNING_TRACING) {
          LOGGER.warn(String.format("**** Iteration=%d, gnorm=%g, xnorm=%g, gnorm/xnorm=%g",
              its - 1, gNormLast, xnorm, gNormLast / xnorm));
        }

        if (gNormLast < EPS * xnorm) {
          // |g| < max(1,|x|)
          // Now actually compare with the two norm if we have to.
          System.err.println("Gradient is numerically zero, stopped on machine epsilon.");
          return eState.TERMINATE_GRADNORM;
        }
      } else {
        if (MPGConfig.SHOW_RUNNING_TRACING) {
          LOGGER.warn(String.format("**** Iteration=%d, gnorm=%g, xnorm=%g, gnorm/xnorm=%g",
              its - 1, gNormLast, xnorm1, gNormLast / xnorm1));
        }
      }

      // give user information about the norms.
      say(" |" + nf.format(gNormLast) + "| {" + nf.format(relNorm) + "} "
          + nf.format(Math.abs(averageImprovement / newestVal)) + " ");
      return eState.CONTINUE;
    }

    /**
     * Return the time in seconds since this class was created.
     * 
     * @return The time in seconds since this class was created.
     */
    private double howLong() {
      return ((System.currentTimeMillis() - startTime)) / 1000.0;
    }
  } // end class Record

  /**
   * The QNInfo class is used to store information about the Quasi Newton update. it holds all the
   * s,y pairs, updates the diagonal and scales everything as needed.
   */
  private class QNInfo {
    // Diagonal Options
    // Linesearch Options
    // Memory stuff
    private List<double[]> s = null;
    private List<double[]> y = null;
    private List<Double> rho = null;
    private double gamma;
    private double[] d = null;
    private int mem;
    private int maxMem = 20;
    public eScaling scaleOpt = eScaling.SCALAR;

    private QNInfo(int size) {
      s = new ArrayList<>();
      y = new ArrayList<>();
      rho = new ArrayList<>();
      gamma = 1;
      mem = size;
    }

    private int size() {
      return s.size();
    }

    private double getRho(int ind) {
      return rho.get(ind);
    }

    private double[] getS(int ind) {
      return s.get(ind);
    }

    private double[] getY(int ind) {
      return y.get(ind);
    }

    /*
     * Free up that memory.
     */
    private void free() {
      s = null;
      y = null;
      rho = null;
      d = null;
    }

    private void clear() {
      s.clear();
      y.clear();
      rho.clear();
      d = null;
    }

    private double[] applyInitialHessian(double[] x) {

      switch (scaleOpt) {
        case SCALAR:
          say("I");
          ArrayMath.multiplyInPlace(x, gamma);
          break;
        case DIAGONAL:
          say("D");
          if (d != null) {
            // Check sizes
            if (x.length != d.length) {
              throw new IllegalArgumentException(
                  "Vector of incorrect size passed to applyInitialHessian in QNInfo class");
            }
            // Scale element-wise
            for (int i = 0; i < x.length; i++) {
              x[i] = x[i] / (d[i]);
            }
          }
          break;
      }

      return x;

    }

    /*
     * The update function is used to update the hessian approximation used by the quasi newton
     * optimization routine.
     * 
     * If everything has behaved nicely, this involves deciding on a new initial hessian through
     * scaling or diagonal update, and then storing of the secant pairs s = x - previousX and y =
     * grad - previousGrad.
     * 
     * Things can go wrong, if any non convex behavior is detected (s^T y &lt; 0) or numerical
     * errors are likely the update is skipped.
     */
    private int update(double[] newX, double[] x, double[] newGrad, double[] grad, double step)
        throws SurpriseConvergence {
      // todo: add OutOfMemory error.
      double[] newS, newY;
      double sy, yy, sg;

      // allocate arrays for new s,y pairs (or replace if the list is already
      // full)
      if (mem > 0 && s.size() == mem || s.size() == maxMem) {
        newS = s.remove(0);
        newY = y.remove(0);
        rho.remove(0);
      } else {
        newS = new double[x.length];
        newY = new double[x.length];
      }

      // Here we construct the new pairs, and check for positive definiteness.
      sy = 0;
      yy = 0;
      sg = 0;
      for (int i = 0; i < x.length; i++) {
        newS[i] = newX[i] - x[i];
        newY[i] = newGrad[i] - grad[i];
        sy += newS[i] * newY[i];
        yy += newY[i] * newY[i];
        sg += newS[i] * newGrad[i];
      }

      // Apply the updates used for the initial hessian.

      return update(newS, newY, yy, sy, sg, step);
    }

    private class NegativeCurvature extends Throwable {
      /**
       *
       */
      private static final long serialVersionUID = 4676562552506850519L;

      public NegativeCurvature() {}
    }

    private class ZeroGradient extends Throwable {
      /**
       *
       */
      private static final long serialVersionUID = -4001834044987928521L;

      public ZeroGradient() {}
    }

    private int update(double[] newS, double[] newY, double yy, double sy, double sg, double step) {

      // Initialize diagonal to the identity
      if (scaleOpt == eScaling.DIAGONAL && d == null) {
        d = new double[newS.length];
        for (int i = 0; i < d.length; i++) {
          d[i] = 1.0;
        }
      }

      try {

        if (sy < 0) {
          throw new NegativeCurvature();
        }

        if (yy == 0.0) {
          throw new ZeroGradient();
        }

        switch (scaleOpt) {
          /*
           * SCALAR: The standard L-BFGS initial approximation which is just a scaled identity.
           */
          case SCALAR:
            gamma = sy / yy;
            break;
          /*
           * DIAGONAL: A diagonal scaling matrix is used as the initial approximation. The updating
           * method used is used thanks to Andrew Bradley of the ICME dept.
           */
          case DIAGONAL:

            double sDs;
            // Gamma is designed to scale such that a step length of one is
            // generally accepted.
            gamma = sy / (step * (sy - sg));
            sDs = 0.0;
            for (int i = 0; i < d.length; i++) {
              d[i] = gamma * d[i];
              sDs += newS[i] * d[i] * newS[i];
            }
            // This diagonal update was introduced by Andrew Bradley
            for (int i = 0; i < d.length; i++) {
              d[i] = (1 - d[i] * newS[i] * newS[i] / sDs) * d[i] + newY[i] * newY[i] / sy;
            }
            // Here we make sure that the diagonal is alright
            double minD = ArrayMath.min(d);
            double maxD = ArrayMath.max(d);

            // If things have gone bad, just fill with the SCALAR approx.
            if (minD <= 0 || Double.isInfinite(maxD) || maxD / minD > 1e12) {
              System.err.println("QNInfo:update() : PROBLEM WITH DIAGONAL UPDATE");
              double fill = yy / sy;
              for (int i = 0; i < d.length; i++) {
                d[i] = fill;
              }
            }

        }

        // If s is already of size mem, remove the oldest vector and free it up.

        if (mem > 0 && s.size() == mem || s.size() == maxMem) {
          s.remove(0);
          y.remove(0);
          rho.remove(0);
        }

        // Actually add the pair.
        s.add(newS);
        y.add(newY);
        rho.add(1 / sy);

      } catch (NegativeCurvature nc) {
        // NOTE: if applying QNMinimizer to a non convex problem, we would still
        // like to update the matrix
        // or we could get stuck in a series of skipped updates.
        say(" Negative curvature detected, update skipped ");
      } catch (ZeroGradient zg) {
        say(" Either convergence, or floating point errors combined with extremely linear region ");
      }

      return s.size();
    } // end update

  } // end class QNInfo

  /*
   * computeDir()
   * 
   * This function will calculate an approximation of the inverse hessian based off the seen s,y
   * vector pairs. This particular approximation uses the BFGS update.
   */

  private void computeDir(double[] dir, double[] fg, double[] x, QNInfo qn)
      throws SurpriseConvergence {
    System.arraycopy(fg, 0, dir, 0, fg.length);

    int mmm = qn.size();
    double[] as = new double[mmm];

    for (int i = mmm - 1; i >= 0; i--) {
      as[i] = qn.getRho(i) * ArrayMath.innerProduct(qn.getS(i), dir);
      plusAndConstMult(dir, qn.getY(i), -as[i], dir);
    }

    // multiply by hessian approximation
    qn.applyInitialHessian(dir);

    for (int i = 0; i < mmm; i++) {
      double b = qn.getRho(i) * ArrayMath.innerProduct(qn.getY(i), dir);
      plusAndConstMult(dir, qn.getS(i), as[i] - b, dir);
    }

    ArrayMath.multiplyInPlace(dir, -1);
  }

  // computes d = a + b * c
  private static double[] plusAndConstMult(double[] a, double[] b, double c, double[] d) {
    for (int i = 0; i < a.length; i++) {
      d[i] = a[i] + c * b[i];
    }
    return d;
  }

  public double[] minimize(MinimizationObjectiveFunction function, double[] initial,
      IterationCallback iterationCallback) {
    return minimize(function, initial, -1, iterationCallback);
  }

  private double[] minimize(MinimizationObjectiveFunction function, double[] initial,
      int maxFunctionEvaluations, IterationCallback iterationCallback) {
    if (mem > 0) {
      sayln(" using M = " + mem + '.');
    } else {
      sayln(" using dynamic setting of M.");
    }

    QNInfo qn = new QNInfo(mem);

    double[] x, newX, rawGrad, grad, newGrad, dir;
    double value;
    its = 0;
    fevals = 0;
    success = false;

    qn.scaleOpt = scaleOpt;

    // initialize weights
    x = initial;

    // initialize gradient
    rawGrad = new double[x.length];
    newGrad = new double[x.length];
    newX = new double[x.length];
    dir = new double[x.length];

    // initialize function value and gradient (gradient is stored in grad inside
    // evaluateFunction)
    value = evaluateFunction(function, x, rawGrad);
    grad = rawGrad;

    Record rec = new Record();
    // sets the original gradient and x. Also stores the monitor.
    rec.start(value, rawGrad, x);

    // Check if max Evaluations and Iterations have been provided.
    maxFevals = (maxFunctionEvaluations > 0) ? maxFunctionEvaluations : Integer.MAX_VALUE;
    // maxIterations = (maxIterations > 0) ? maxIterations : Integer.MAX_VALUE;

    sayln("               An explanation of the output:");
    sayln("Iter           The number of iterations");
    sayln("evals          The number of function evaluations");
    sayln("SCALING        <D> Diagonal scaling was used; <I> Scaled Identity");
    sayln("LINESEARCH     [## M steplength]  Minpack linesearch");
    sayln("                   1-Function value was too high");
    sayln("                   2-Value ok, gradient positive, positive curvature");
    sayln("                   3-Value ok, gradient negative, positive curvature");
    sayln("                   4-Value ok, gradient negative, negative curvature");
    sayln("               [.. B]  Backtracking");
    sayln("VALUE          The current function value");
    sayln("TIME           Total elapsed time");
    sayln("|GNORM|        The current norm of the gradient");
    sayln("{RELNORM}      The ratio of the current to initial gradient norms");
    sayln("AVEIMPROVE     The average improvement / current value");
    sayln("EVALSCORE      The last available eval score");
    sayln();
    sayln(
        "Iter ## evals ## <SCALING> [LINESEARCH] VALUE TIME |GNORM| {RELNORM} AVEIMPROVE EVALSCORE");

    // Beginning of the loop.
    do {
      try {
        sayln();
        its += 1;
        double newValue;
        say("Iter " + its + " evals " + fevals + ' ');

        // Compute the search direction
        say("<");
        computeDir(dir, grad, x, qn);
        say("> ");

        // sanity check dir
        boolean hasNaNDir = false;
        boolean hasNaNGrad = false;
        for (int i = 0; i < dir.length; i++) {
          if (dir[i] != dir[i])
            hasNaNDir = true;
          if (grad[i] != grad[i])
            hasNaNGrad = true;
        }
        if (hasNaNDir && !hasNaNGrad) {
          say("(NaN dir likely due to Hessian approx - resetting) ");
          qn.clear();
          // re-compute the search direction
          say("<");
          computeDir(dir, grad, x, qn);
          say("> ");
        }

        // perform line search
        say("[");

        double[] newPoint = lineSearchMinPack(function, dir, x, newX, grad, value,
            MPGConfig.LBFGS_TERMINATE_VALUE_TOLERANCE);

        newValue = newPoint[f];
        say(" ");
        say(nf.format(newPoint[a]));
        say("] ");

        // This shouldn't actually evaluate anything since that should have been
        // done in the lineSearch.
        System.arraycopy(function.getGradients(newX), 0, newGrad, 0, newGrad.length);

        // This is where all the s, y updates are applied.
        qn.update(newX, x, newGrad, rawGrad, newPoint[a]); // step (4) in Galen & Gao 2007

        // Add the current value and gradient to the records, this also monitors
        // X and writes to output
        rec.add(newValue, newGrad, newX, fevals);

        // shift
        value = newValue;
        // double[] temp = x;
        // x = newX;
        // newX = temp;
        System.arraycopy(newX, 0, x, 0, x.length);
        System.arraycopy(newGrad, 0, grad, 0, newGrad.length);

        if (fevals > maxFevals) {
          throw new MaxEvaluationsExceeded(" Exceeded in minimize() loop ");
        }

      } catch (SurpriseConvergence s) {
        sayln();
        sayln("QNMinimizer aborted due to surprise convergence");
        break;
      } catch (MaxEvaluationsExceeded m) {
        sayln();
        sayln("QNMinimizer aborted due to maximum number of function evaluations");
        sayln(m.toString());
        sayln("** This is not an acceptable termination of QNMinimizer, consider");
        sayln("** increasing the max number of evaluations, or safeguarding your");
        sayln("** program by checking the QNMinimizer.wasSuccessful() method.");
        break;
      } catch (OutOfMemoryError oome) {
        sayln();
        if (!qn.s.isEmpty()) {
          qn.s.remove(0);
          qn.y.remove(0);
          qn.rho.remove(0);
          qn.mem = qn.s.size();
          System.err.println("Caught OutOfMemoryError, changing m = " + qn.mem);
        } else {
          throw oome;
        }
      }

      if (iterationCallback != null) {
        try {
          iterationCallback.call(its - 1, x);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    } while ((state = rec.toContinue()) == eState.CONTINUE); // end do while

    //
    // Announce the reason minimization has terminated.
    //
    System.err.println();
    switch (state) {
      case TERMINATE_GRADNORM:
        System.err.println(
            "QNMinimizer terminated due to numerically zero gradient: |g| < EPS  max(1,|x|) ");
        success = true;
        break;
      case TERMINATE_RELATIVENORM:
        System.err.println(
            "QNMinimizer terminated due to sufficient decrease in gradient norms: |g|/|g0| < TOL ");
        success = true;
        break;
      case TERMINATE_AVERAGEIMPROVE:
        System.err.println(
            "QNMinimizer terminated due to average improvement: | newest_val - previous_val | / |newestVal| < TOL ");
        success = true;
        break;
      case TERMINATE_MAXITR:
        System.err.println("QNMinimizer terminated due to reached max iteration " + maxItr);
        success = true;
        break;
      default:
        System.err.println("QNMinimizer terminated without converging");
        success = false;
        break;
    }

    double completionTime = rec.howLong();
    sayln("Total time spent in optimization: " + nfsec.format(completionTime) + "s");

    qn.free();
    return x;

  } // end minimize()

  private void sayln() {
    if (!quiet) {
      System.err.println();
    }
  }

  private void sayln(String s) {
    if (!quiet) {
      System.err.println(s);
    }
  }

  private void say(String s) {
    if (!quiet) {
      System.err.print(s);
    }
  }

  // todo [cdm 2013]: Can this be sped up by returning a Pair rather than copying array?
  private double evaluateFunction(MinimizationObjectiveFunction func, double[] x, double[] grad) {
    Pair<Double, double[]> valueAndGradients = func.getValueAndGradients(x);
    System.arraycopy(valueAndGradients.getRight(), 0, grad, 0, grad.length);
    fevals += 1;
    return valueAndGradients.getLeft();
  }

  private double[] lineSearchMinPack(MinimizationObjectiveFunction dfunc, double[] dir, double[] x,
      double[] newX, double[] grad, double f0, double tol) throws MaxEvaluationsExceeded {
    double xtrapf = 4.0;
    int info = 0;
    int infoc = 1;
    bracketed = false;
    boolean stage1 = true;
    double width = aMax - aMin;
    double width1 = 2 * width;
    // double[] wa = x;

    // Should check input parameters

    double g0 = ArrayMath.innerProduct(grad, dir);
    if (g0 >= 0) {
      // We're looking in a direction of positive gradient. This won't work.
      // set dir = -grad
      for (int i = 0; i < x.length; i++) {
        dir[i] = -grad[i];
      }
      g0 = ArrayMath.innerProduct(grad, dir);
    }
    double gTest = ftol * g0;

    double[] newPt = new double[3];
    double[] bestPt = new double[3];
    double[] endPt = new double[3];

    newPt[a] = 1.0; // Always guess 1 first, this should be right if the
                    // function is "nice" and BFGS is working.

    if (its == 1) {
      newPt[a] = 1e-1;
    }

    bestPt[a] = 0.0;
    bestPt[f] = f0;
    bestPt[g] = g0;
    endPt[a] = 0.0;
    endPt[f] = f0;
    endPt[g] = g0;

    // int cnt = 0;

    do {

      double stpMin; // = aMin; [cdm: this initialization was always overridden below]
      double stpMax; // = aMax; [cdm: this initialization was always overridden below]
      if (bracketed) {
        stpMin = Math.min(bestPt[a], endPt[a]);
        stpMax = Math.max(bestPt[a], endPt[a]);
      } else {
        stpMin = bestPt[a];
        stpMax = newPt[a] + xtrapf * (newPt[a] - bestPt[a]);
      }

      newPt[a] = Math.max(newPt[a], aMin);
      newPt[a] = Math.min(newPt[a], aMax);

      // Use the best point if we have some sort of strange termination
      // conditions.
      if ((bracketed && (newPt[a] <= stpMin || newPt[a] >= stpMax)) || fevals >= maxFevals
          || infoc == 0 || (bracketed && stpMax - stpMin <= tol * stpMax)) {
        // todo: below..
        plusAndConstMult(x, dir, bestPt[a], newX);
        newPt[f] = bestPt[f];
        newPt[a] = bestPt[a];
      }

      newX = plusAndConstMult(x, dir, newPt[a], newX);
      Pair<Double, double[]> valueAndGradients = dfunc.getValueAndGradients(newX);
      newPt[f] = valueAndGradients.getLeft();
      newPt[g] = ArrayMath.innerProduct(valueAndGradients.getRight(), dir);
      double fTest = f0 + newPt[a] * gTest;
      fevals += 1;

      // Check and make sure everything is normal.
      if ((bracketed && (newPt[a] <= stpMin || newPt[a] >= stpMax)) || infoc == 0) {
        info = 6;
        say(" line search failure: bracketed but no feasible found ");
      }
      if (newPt[a] == aMax && newPt[f] <= fTest && newPt[g] <= gTest) {
        info = 5;
        say(" line search failure: sufficient decrease, but gradient is more negative ");
      }
      if (newPt[a] == aMin && (newPt[f] > fTest || newPt[g] >= gTest)) {
        info = 4;
        say(" line search failure: minimum step length reached ");
      }
      if (fevals >= maxFevals) {
        info = 3;
        throw new MaxEvaluationsExceeded(" Exceeded during lineSearchMinPack() Function ");
      }
      if (bracketed && stpMax - stpMin <= tol * stpMax) {
        info = 2;
        say(" line search failure: interval is too small ");
      }
      if (newPt[f] <= fTest && Math.abs(newPt[g]) <= -gtol * g0) {
        info = 1;
      }

      if (info != 0) {
        return newPt;
      }

      // this is the first stage where we look for a point that is lower and
      // increasing

      if (stage1 && newPt[f] <= fTest && newPt[g] >= Math.min(ftol, gtol) * g0) {
        stage1 = false;
      }

      // A modified function is used to predict the step only if
      // we have not obtained a step for which the modified
      // function has a non-positive function value and non-negative
      // derivative, and if a lower function value has been
      // obtained but the decrease is not sufficient.

      if (stage1 && newPt[f] <= bestPt[f] && newPt[f] > fTest) {
        newPt[f] = newPt[f] - newPt[a] * gTest;
        bestPt[f] = bestPt[f] - bestPt[a] * gTest;
        endPt[f] = endPt[f] - endPt[a] * gTest;

        newPt[g] = newPt[g] - gTest;
        bestPt[g] = bestPt[g] - gTest;
        endPt[g] = endPt[g] - gTest;

        infoc = getStep(/* x, dir, newX, f0, g0, */
            newPt, bestPt, endPt, stpMin, stpMax);

        bestPt[f] = bestPt[f] + bestPt[a] * gTest;
        endPt[f] = endPt[f] + endPt[a] * gTest;

        bestPt[g] = bestPt[g] + gTest;
        endPt[g] = endPt[g] + gTest;
      } else {
        infoc = getStep(/* x, dir, newX, f0, g0, */
            newPt, bestPt, endPt, stpMin, stpMax);
      }

      if (bracketed) {
        if (Math.abs(endPt[a] - bestPt[a]) >= p66 * width1) {
          newPt[a] = bestPt[a] + p5 * (endPt[a] - bestPt[a]);
        }
        width1 = width;
        width = Math.abs(endPt[a] - bestPt[a]);
      }

    } while (true);
  }

  /**
   * getStep()
   *
   * THIS FUNCTION IS A TRANSLATION OF A TRANSLATION OF THE MINPACK SUBROUTINE cstep(). Dianne
   * O'Leary July 1991
   *
   * It was then interpreted from the implementation supplied by Andrew Bradley. Modifications have
   * been made for this particular application.
   *
   * This function is used to find a new safe guarded step to be used for line search procedures.
   *
   */
  private int getStep(
      /*
       * double[] x, double[] dir, double[] newX, double f0, double g0, // None of these were used
       */
      double[] newPt, double[] bestPt, double[] endPt, double stpMin, double stpMax)
      throws MaxEvaluationsExceeded {

    // Should check for input errors.
    int info; // = 0; always set in the if below
    boolean bound; // = false; always set in the if below
    double theta, gamma, p, q, r, s, stpc, stpq, stpf;
    double signG = newPt[g] * bestPt[g] / Math.abs(bestPt[g]);

    //
    // First case. A higher function value.
    // The minimum is bracketed. If the cubic step is closer
    // to stx than the quadratic step, the cubic step is taken,
    // else the average of the cubic and quadratic steps is taken.
    //
    if (newPt[f] > bestPt[f]) {
      info = 1;
      bound = true;
      theta = 3 * (bestPt[f] - newPt[f]) / (newPt[a] - bestPt[a]) + bestPt[g] + newPt[g];
      s = Math.max(Math.max(theta, newPt[g]), bestPt[g]);
      gamma = // could be NaN if we do not use Math.max(0.0, ...)
          s * Math
              .sqrt(Math.max(0.0, (theta / s) * (theta / s) - (bestPt[g] / s) * (newPt[g] / s)));
      if (newPt[a] < bestPt[a]) {
        gamma = -gamma;
      }
      p = (gamma - bestPt[g]) + theta;
      q = ((gamma - bestPt[g]) + gamma) + newPt[g];
      r = p / q;
      stpc = bestPt[a] + r * (newPt[a] - bestPt[a]);
      stpq = bestPt[a]
          + ((bestPt[g] / ((bestPt[f] - newPt[f]) / (newPt[a] - bestPt[a]) + bestPt[g])) / 2)
              * (newPt[a] - bestPt[a]);

      if (Math.abs(stpc - bestPt[a]) < Math.abs(stpq - bestPt[a])) {
        stpf = stpc;
      } else {
        stpf = stpq;
        // stpf = stpc + (stpq - stpc)/2;
      }
      bracketed = true;
      if (newPt[a] < 0.1) {
        stpf = 0.01 * stpf;
      }

    } else if (signG < 0.0) {
      //
      // Second case. A lower function value and derivatives of
      // opposite sign. The minimum is bracketed. If the cubic
      // step is closer to stx than the quadratic (secant) step,
      // the cubic step is taken, else the quadratic step is taken.
      //
      info = 2;
      bound = false;
      theta = 3 * (bestPt[f] - newPt[f]) / (newPt[a] - bestPt[a]) + bestPt[g] + newPt[g];
      s = Math.max(Math.max(theta, bestPt[g]), newPt[g]);
      gamma = // could be NaN if we do not use Math.max(0.0, ...)
          s * Math
              .sqrt(Math.max(0.0, (theta / s) * (theta / s) - (bestPt[g] / s) * (newPt[g] / s)));
      if (newPt[a] > bestPt[a]) {
        gamma = -gamma;
      }
      p = (gamma - newPt[g]) + theta;
      q = ((gamma - newPt[g]) + gamma) + bestPt[g];
      r = p / q;
      stpc = newPt[a] + r * (bestPt[a] - newPt[a]);
      stpq = newPt[a] + (newPt[g] / (newPt[g] - bestPt[g])) * (bestPt[a] - newPt[a]);
      if (Math.abs(stpc - newPt[a]) > Math.abs(stpq - newPt[a])) {
        stpf = stpc;
      } else {
        stpf = stpq;
      }
      bracketed = true;

    } else if (Math.abs(newPt[g]) < Math.abs(bestPt[g])) {
      //
      // Third case. A lower function value, derivatives of the
      // same sign, and the magnitude of the derivative decreases.
      // The cubic step is only used if the cubic tends to infinity
      // in the direction of the step or if the minimum of the cubic
      // is beyond stp. Otherwise the cubic step is defined to be
      // either stpmin or stpmax. The quadratic (secant) step is also
      // computed and if the minimum is bracketed then the the step
      // closest to stx is taken, else the step farthest away is taken.
      //
      info = 3;
      bound = true;
      theta = 3 * (bestPt[f] - newPt[f]) / (newPt[a] - bestPt[a]) + bestPt[g] + newPt[g];
      s = Math.max(Math.max(theta, bestPt[g]), newPt[g]);
      gamma = // could be NaN if we do not use Math.max(0.0, ...)
          s * Math
              .sqrt(Math.max(0.0, (theta / s) * (theta / s) - (bestPt[g] / s) * (newPt[g] / s)));
      if (newPt[a] < bestPt[a]) {
        gamma = -gamma;
      }
      p = (gamma - bestPt[g]) + theta;
      q = ((gamma - bestPt[g]) + gamma) + newPt[g];
      r = p / q;
      if (r < 0.0 && gamma != 0.0) {
        stpc = newPt[a] + r * (bestPt[a] - newPt[a]);
      } else if (newPt[a] > bestPt[a]) {
        stpc = stpMax;
      } else {
        stpc = stpMin;
      }
      stpq = newPt[a] + (newPt[g] / (newPt[g] - bestPt[g])) * (bestPt[a] - newPt[a]);

      if (bracketed) {
        if (Math.abs(newPt[a] - stpc) < Math.abs(newPt[a] - stpq)) {
          stpf = stpc;
        } else {
          stpf = stpq;
        }
      } else {
        if (Math.abs(newPt[a] - stpc) > Math.abs(newPt[a] - stpq)) {
          stpf = stpc;
        } else {
          stpf = stpq;
        }
      }

    } else {
      //
      // Fourth case. A lower function value, derivatives of the
      // same sign, and the magnitude of the derivative does
      // not decrease. If the minimum is not bracketed, the step
      // is either stpmin or stpmax, else the cubic step is taken.
      //
      info = 4;
      bound = false;

      if (bracketed) {
        theta = 3 * (bestPt[f] - newPt[f]) / (newPt[a] - bestPt[a]) + bestPt[g] + newPt[g];
        s = Math.max(Math.max(theta, bestPt[g]), newPt[g]);
        gamma = // could be NaN if we do not use Math.max(0.0, ...)
            s * Math
                .sqrt(Math.max(0.0, (theta / s) * (theta / s) - (bestPt[g] / s) * (newPt[g] / s)));
        if (newPt[a] > bestPt[a]) {
          gamma = -gamma;
        }
        p = (gamma - newPt[g]) + theta;
        q = ((gamma - newPt[g]) + gamma) + bestPt[g];
        r = p / q;
        stpc = newPt[a] + r * (bestPt[a] - newPt[a]);
        stpf = stpc;
      } else if (newPt[a] > bestPt[a]) {
        stpf = stpMax;
      } else {
        stpf = stpMin;
      }

    }

    //
    // Update the interval of uncertainty. This update does not
    // depend on the new step or the case analysis above.
    //
    if (newPt[f] > bestPt[f]) {
      copy(newPt, endPt);
    } else {
      if (signG < 0.0) {
        copy(bestPt, endPt);
      }
      copy(newPt, bestPt);
    }

    say(String.valueOf(info));

    //
    // Compute the new step and safeguard it.
    //
    stpf = Math.min(stpMax, stpf);
    stpf = Math.max(stpMin, stpf);
    newPt[a] = stpf;

    if (bracketed && bound) {
      if (endPt[a] > bestPt[a]) {
        newPt[a] = Math.min(bestPt[a] + p66 * (endPt[a] - bestPt[a]), newPt[a]);
      } else {
        newPt[a] = Math.max(bestPt[a] + p66 * (endPt[a] - bestPt[a]), newPt[a]);
      }
    }

    return info;
  }

  private static void copy(double[] src, double[] dest) {
    System.arraycopy(src, 0, dest, 0, src.length);
  }
}
