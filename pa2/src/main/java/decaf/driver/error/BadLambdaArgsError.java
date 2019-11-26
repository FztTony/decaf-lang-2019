package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：lambda expression expects 3 argument(s) but 2 given<br>
 * PA2
 */
public class BadLambdaArgsError extends DecafError {

    private int count1;

    private int count2;

    public BadLambdaArgsError(Pos pos, int count1, int count2) {
        super(pos);
        this.count1 = count1;
        this.count2 = count2;
    }

    @Override
    protected String getErrMsg() {
        return "lambda expression expects " + count1 + " argument(s) but " + count2 + " given";
    }

}
