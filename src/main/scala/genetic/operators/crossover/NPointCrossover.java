package genetic.operators.crossover;

import org.apache.commons.lang3.ArrayUtils;
import org.uma.jmetal.operator.CrossoverOperator;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by FlapKap on 23-03-2017.
 *
 * fixed by agvico.
 */
public class NPointCrossover<T> implements CrossoverOperator<Solution<T>> {
    private JMetalRandom randomNumberGenerator;
    private final double probability;
    private final int crossovers;

    public NPointCrossover(double probability, int crossovers, JMetalRandom rand) {
        if (probability < 0.0) throw new JMetalException("Probability can't be negative");
        if (crossovers < 1) throw new JMetalException("Number of crossovers is less than one");
        this.probability = probability;
        this.crossovers = crossovers;
        this.randomNumberGenerator = rand;
    }

    public NPointCrossover(int crossovers, JMetalRandom rand) {
        this.crossovers = crossovers;
        this.probability = 1.0;
        this.randomNumberGenerator = rand;
    }

    public double getCrossoverProbability() {
        return probability;
    }

    @Override
    public List<Solution<T>> execute(List<Solution<T>> s) {
        if (getNumberOfRequiredParents() != s.size()) {
            throw new JMetalException("Point Crossover requires + " + getNumberOfRequiredParents() + " parents, but got " + s.size());
        }
        if (randomNumberGenerator.nextDouble() < probability) {
            return doCrossover(s);
        } else {
            ArrayList<Solution<T>> parents = new ArrayList<>();
            parents.add(s.get(0).copy());
            parents.add(s.get(1).copy());
            return parents;
            //return s;
        }
    }

    private List<Solution<T>> doCrossover(List<Solution<T>> s) {
        Solution<T> mom = s.get(0);
        Solution<T> dad = s.get(1);

        if (mom.getNumberOfVariables() != dad.getNumberOfVariables()) {
            throw new JMetalException("The 2 parents doesn't have the same number of variables");
        }
        if (mom.getNumberOfVariables() < crossovers) {
            throw new JMetalException("The number of crossovers is higher than the number of variables");
        }

        int[] crossoverPoints = new int[crossovers];
        for (int i = 0; i < crossoverPoints.length; i++) {
            crossoverPoints[i] = randomNumberGenerator.nextInt(0, mom.getNumberOfVariables() - 1);
        }
        Solution<T> girl = mom.copy();
        Solution<T> boy = dad.copy();
        boolean swap = false;

        for (int i = 0; i < mom.getNumberOfVariables(); i++) {
            if (swap) {
                boy.setVariableValue(i, mom.getVariableValue(i));
                girl.setVariableValue(i, dad.getVariableValue(i));

            }

            if (ArrayUtils.contains(crossoverPoints, i)) {
                swap = !swap;
            }
        }
        List<Solution<T>> result = new ArrayList<>();
        result.add(girl);
        result.add(boy);
        return result;
    }

    @Override
    public int getNumberOfRequiredParents() {
        return 2;
    }

    @Override
    public int getNumberOfGeneratedChildren() {
        return 2;
    }
}

