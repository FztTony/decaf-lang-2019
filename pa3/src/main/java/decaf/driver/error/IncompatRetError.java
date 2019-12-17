package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：incompatible return types in blocked expression<br>
 * PA2
 */
public class IncompatRetError extends DecafError {

    public IncompatRetError(Pos pos) {
        super(pos);
    }

    @Override
    protected String getErrMsg() {
        return "incompatible return types in blocked expression";
    }
}