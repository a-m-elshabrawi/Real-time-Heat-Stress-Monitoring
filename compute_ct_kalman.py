import pandas as pd
import numpy as np

def run_kalman_filter(hr_series, ct_start=37.0):
    """
    Estimates Core Temperature (CT) from Heart Rate (HR) using an Extended Kalman Filter.
    Based on the mathematical model provided.
    """
    # Parameters
    a = 1.0
    gamma = 0.022**2
    b0 = -7887.1
    b1 = 384.4286
    b2 = -4.5714
    sigma = 18.88**2
    
    # Initialize state and covariance
    x = ct_start
    v = 0.0 # Assumes confidence in start value
    
    ct_estimates = []
    
    for z in hr_series:
        # 1. Time Update Phase (Predict)
        x_pred = a * x
        v_pred = (a**2) * v + gamma
        
        # 2. Observation Update Phase (Correct)
        # Jacobian H = dh/dx
        c_vc = 2.0 * b2 * x_pred + b1
        
        # Kalman Gain
        k = (v_pred * c_vc) / ((c_vc**2) * v_pred + sigma)
        
        # Measurement function h(x)
        h_x = b2 * (x_pred**2) + b1 * x_pred + b0
        
        # Update state and covariance
        x = x_pred + k * (z - h_x)
        v = (1.0 - k * c_vc) * v_pred
        
        ct_estimates.append(x)
        
    return ct_estimates

def main():
    input_file = "data/(v6.3)synthetic_scenarios_dataset_may_to_sep.csv"
    output_file = "data/(v6.3)synthetic_scenarios_dataset_may_to_sep.csv"
    
    print(f"Reading dataset from {input_file}...")
    df = pd.read_csv(input_file)
    
    if 'hr' not in df.columns:
        print("Error: 'hr' column not found in dataset.")
        return

    update_mask = df['CT'].isna() | df['CT'].eq(0)
    if not update_mask.any():
        print("No unprocessed CT rows found; existing values were preserved.")
        return
        
    print("Computing Core Temperature (CT) using Kalman Filter on 1-minute interpolated data...")
    
    extension = df.loc[update_mask, ['ts', 'hr']].copy()
    extension['ts_dt'] = pd.to_datetime(extension['ts'])
    
    # Sort to ensure chronological order
    extension = extension.sort_values('ts_dt')
    
    # Resample to 1-minute intervals and interpolate HR
    df_1min = extension.set_index('ts_dt')[['hr']].resample('1min').interpolate(method='linear')
    
    # Run Kalman Filter on 1-minute data
    ct_estimates = run_kalman_filter(df_1min['hr'].values, ct_start=37.0)
    df_1min['CT_temp'] = ct_estimates
    
    # Downsample back to 10-minute intervals
    df_10min = df_1min[['CT_temp']].resample('10min').first().reset_index()
    
    # Map estimates back only to extension rows.
    ct_by_time = df_10min.set_index('ts_dt')['CT_temp']
    extension_ct = extension['ts_dt'].map(ct_by_time).round(2)
    df.loc[extension.index, 'CT'] = extension_ct
    df_final = df
    print(f"Updated {update_mask.sum():,} rows; preserved {(~update_mask).sum():,}.")
    
    print(f"Saving updated dataset to {output_file}...")
    df_final.to_csv(output_file, index=False)
    
    # Statistics
    print("\nCT Distribution Summary:")
    print(f"Mean: {df_final['CT'].mean():.2f} °C")
    print(f"Max:  {df_final['CT'].max():.2f} °C")
    print(f"Min:  {df_final['CT'].min():.2f} °C")
    
    print("\nDone. CT column updated.")

if __name__ == "__main__":
    main()
