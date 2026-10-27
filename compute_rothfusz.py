import pandas as pd
import math
import numpy as np
import os

def compute_rothfusz_hi(T_C, RH):
    """
    Computes the Heat Index (HI) using the NOAA Rothfusz regression equation.
    T_C: Temperature in Celsius
    RH: Relative Humidity (%)
    Returns: HI in Celsius
    """
    # Convert Celsius to Fahrenheit for the formula
    T = T_C * 1.8 + 32
    
    # Base Rothfusz regression equation
    HI = -42.379 + 2.04901523*T + 10.14333127*RH - 0.22475541*T*RH - 0.00683783*T*T - 0.05481717*RH*RH + 0.00122874*T*T*RH + 0.00085282*T*RH*RH - 0.00000199*T*T*RH*RH
    
    # Adjustments for extreme conditions
    if RH < 13 and 80 <= T <= 112:
        adjustment = ((13 - RH) / 4) * math.sqrt((17 - abs(T - 95.0)) / 17)
        HI -= adjustment
    elif RH > 85 and 80 <= T <= 87:
        adjustment = ((RH - 85) / 10) * ((87 - T) / 5)
        HI += adjustment
        
    # Convert HI back to Celsius
    HI_C = (HI - 32) * 5.0 / 9.0
    return HI_C

def process_dataset(input_path, output_path, temp_col, hum_col, target_col='HI_Rothfusz'):
    """
    Reads a dataset, computes HI for each row, and saves the result.
    """
    if not os.path.exists(input_path):
        print(f"Error: File not found -> {input_path}")
        return

    print(f"\nProcessing: {input_path}")
    df = pd.read_csv(input_path)
    
    if temp_col not in df.columns or hum_col not in df.columns:
        print(f"Error: Missing columns '{temp_col}' or '{hum_col}' in {input_path}")
        return

    print(f"Computing Heat Index using columns: {temp_col}, {hum_col}...")
    if target_col in df.columns:
        update_mask = df[target_col].isna() | df[target_col].eq(0)
    else:
        df[target_col] = np.nan
        update_mask = pd.Series(True, index=df.index)
    df.loc[update_mask, target_col] = df.loc[update_mask].apply(
        lambda row: compute_rothfusz_hi(row[temp_col], row[hum_col]), axis=1
    )
    print(f"Updated {update_mask.sum():,} rows; preserved {len(df) - update_mask.sum():,}.")
    
    # Rounding for cleanliness
    df[target_col] = df[target_col].round(2)
    
    # Report Statistics
    hi_vals = df[target_col].dropna()
    if not hi_vals.empty:
        print(f"Distribution Summary ({target_col}):")
        print(f" - Mean: {hi_vals.mean():.2f} °C")
        print(f" - Max:  {hi_vals.max():.2f} °C")
        print(f" - 99th Percentile: {np.percentile(hi_vals, 99):.2f} °C")

    print(f"Saving updated dataset to: {output_path}")
    df.to_csv(output_path, index=False)
    print("Done.")

def main():
    # --- CONFIGURATION SECTION ---
    # Edit these values to process different datasets
    
    datasets_to_process = [
        {
            "input": "data/(v6.3)synthetic_scenarios_dataset_may_to_sep.csv",
            "output": "data/(v6.3)synthetic_scenarios_dataset_may_to_sep.csv",
            "temp_col": "temp",
            "hum_col": "w_hum",
            "target_col": "HI_Rothfusz"
        },
        {
            "input": "data/(v5.1)OKBK_may_to_sep.csv",
            "output": "data/(v5.2)OKBK_may_to_sep_rothfusz.csv",
            "temp_col": "temperatur (C)",
            "hum_col": "relative_humidity (%)",
            "target_col": "HI_rothfusz"
        }
    ]
    # -----------------------------

    for config in datasets_to_process:
        process_dataset(
            config['input'], 
            config['output'], 
            config['temp_col'], 
            config['hum_col'],
            config.get('target_col', 'HI_Rothfusz')
        )

if __name__ == "__main__":
    main()
